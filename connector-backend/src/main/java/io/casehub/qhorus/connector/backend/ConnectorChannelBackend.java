package io.casehub.qhorus.connector.backend;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundMessage;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ConnectorChannelBackend implements HumanParticipatingChannelBackend {

    private static final Logger LOG = Logger.getLogger(ConnectorChannelBackend.class);
    private static final String BACKEND_ID = "connector-human";

    private final ChannelGateway gateway;
    private final ChannelService channelService;
    private final ChannelBindingStore bindingStore;
    private final ConnectorService connectorService;
    private final MeterRegistry meterRegistry;
    private final AutoChannelPolicy autoChannelPolicy;

    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public ConnectorChannelBackend(
            final ChannelGateway gateway,
            final ChannelService channelService,
            final ChannelBindingStore bindingStore,
            final ConnectorService connectorService,
            final MeterRegistry meterRegistry,
            final AutoChannelPolicy autoChannelPolicy) {
        this.gateway = gateway;
        this.channelService = channelService;
        this.bindingStore = bindingStore;
        this.connectorService = connectorService;
        this.meterRegistry = meterRegistry;
        this.autoChannelPolicy = autoChannelPolicy;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public ActorType actorType() {
        return ActorType.HUMAN;
    }

    @Override
    public void open(final ChannelRef channel, final Map<String, String> metadata) {
        // no-op — registration is driven by ChannelInitialisedEvent
    }

    @Override
    public void close(final ChannelRef channel) {
        cache.remove(channel.id());
    }

    public void onChannelInitialised(@Observes final ChannelInitialisedEvent event) {
        UUID channelId = event.channelId();
        bindingStore.findByChannelId(channelId).ifPresentOrElse(binding -> {
            cache.put(channelId, new CacheEntry(
                    binding.inboundConnectorId,
                    binding.externalKey,
                    binding.outboundConnectorId,
                    binding.outboundDestination));
            gateway.deregisterBackend(channelId, BACKEND_ID);
            gateway.registerBackend(channelId, this, "human_participating");
        }, () -> {
            // no binding — not a connector-backed channel, skip
        });
    }

    /**
     * Receives an inbound message via CDI async event delivery.
     *
     * <p>Returns {@code CompletionStage<Void>} so that callers using
     * {@code Event.fireAsync().toCompletableFuture().join()} reliably wait for this
     * observer to finish before asserting. The returned stage is always already
     * completed — direct callers (bypassing CDI) may safely ignore the return value.
     */
    public CompletionStage<Void> onInboundMessage(@ObservesAsync final InboundMessage msg) {
        String lookupKey = ConnectorKeyStrategy.deriveKey(msg);

        channelService.findByConnectorKey(msg.connectorId(), lookupKey)
                .or(() -> tryAutoCreate(msg, lookupKey))
                .ifPresentOrElse(
                        channel -> route(channel, msg),
                        () -> {
                            LOG.warnf("No channel for connector=%s key=%s — discarding",
                                    msg.connectorId(), lookupKey);
                            meterRegistry.counter("inbound_messages_discarded_total",
                                    "connector_id", msg.connectorId()).increment();
                        });

        return CompletableFuture.completedFuture(null);
    }

    private void route(Channel channel, InboundMessage msg) {
        gateway.receiveHumanMessage(
                new ChannelRef(channel.id, channel.name),
                new InboundHumanMessage(
                        msg.externalSenderId(),
                        msg.content(),
                        msg.receivedAt(),
                        msg.metadata(),
                        null,
                        null));
    }

    private Optional<Channel> tryAutoCreate(InboundMessage msg, String lookupKey) {
        Optional<AutoChannelSpec> specOpt = autoChannelPolicy.onFirstContact(msg, lookupKey);
        if (specOpt.isEmpty()) {
            return Optional.empty();
        }
        AutoChannelSpec spec = specOpt.get();
        ChannelCreateRequest req = new ChannelCreateRequest(
                spec.channelName(),
                spec.description(),
                spec.semantic(),
                null, null, null, null, null,
                spec.allowedTypes(),
                msg.connectorId(),
                lookupKey,
                spec.outboundConnectorId(),
                spec.outboundDestination());
        try {
            Channel channel = channelService.findOrCreateWithBinding(req);
            meterRegistry.counter("inbound_channels_auto_created_total",
                    "connector_id", msg.connectorId()).increment();
            gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name));
            return Optional.of(channel);
        } catch (PersistenceException ex) {
            if (isConcurrentInsert(ex)) {
                // Race loser: winner's REQUIRES_NEW committed; find their channel.
                // initChannel() is NOT called here — winner already fired it.
                // Thread B's push delivery may miss if winner's initChannel() hasn't run yet;
                // message is still persisted (at-most-once push delivery contract).
                Optional<Channel> recovered = channelService.findByConnectorKey(msg.connectorId(), lookupKey);
                if (recovered.isEmpty()) {
                    LOG.errorf("Race recovery failed: binding exists but channel not found for connector=%s key=%s — discarding",
                            msg.connectorId(), lookupKey);
                    return Optional.empty();
                }
                return recovered;
            }
            LOG.errorf(ex, "DB error auto-creating channel for connector=%s key=%s — discarding",
                    msg.connectorId(), lookupKey);
            return Optional.empty();
        }
    }

    static boolean isConcurrentInsert(PersistenceException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLIntegrityConstraintViolationException c) {
                String msg = c.getMessage() != null ? c.getMessage().toLowerCase() : "";
                return msg.contains("uq_binding_key") || msg.contains("unique");
            }
            // PostgreSQL: PSQLException extends java.sql.SQLException directly (not SQLIntegrityConstraintViolationException).
            // Check message for the constraint name to identify binding-key collisions.
            if (cause instanceof java.sql.SQLException s
                    && !(cause instanceof SQLIntegrityConstraintViolationException)) {
                String msg = s.getMessage() != null ? s.getMessage() : "";
                if (msg.contains("uq_binding_key")) return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void post(final ChannelRef channel, final OutboundMessage message) {
        CacheEntry entry = cache.get(channel.id());
        if (entry == null) {
            LOG.errorf("No cache entry for channel %s (%s) — cannot post outbound message",
                    channel.id(), channel.name());
            return;
        }
        String title = OutboundTitle.forConnector(entry.outboundConnectorId(), channel);
        try {
            connectorService.send(entry.outboundConnectorId(),
                    new ConnectorMessage(entry.outboundDestination(), title, message.content()));
        } catch (IllegalArgumentException ex) {
            LOG.errorf(ex, "Failed to send via connector %s to channel %s (%s)",
                    entry.outboundConnectorId(), channel.id(), channel.name());
        }
    }

    /** Package-private test helper — reads the discarded message counter for a connector. */
    double discardedCount(final String connectorId) {
        return meterRegistry.counter("inbound_messages_discarded_total",
                "connector_id", connectorId).count();
    }

    /** Package-private test helper — reads the auto-created channel counter for a connector. */
    double autoCreatedCount(final String connectorId) {
        return meterRegistry.counter("inbound_channels_auto_created_total",
                "connector_id", connectorId).count();
    }

    private record CacheEntry(
            String inboundConnectorId,
            String externalKey,
            String outboundConnectorId,
            String outboundDestination) {}
}

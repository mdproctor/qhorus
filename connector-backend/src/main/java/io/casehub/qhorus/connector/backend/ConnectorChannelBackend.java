package io.casehub.qhorus.connector.backend;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundMessage;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.FindOrCreateResult;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.api.store.ChannelBindingStore;
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

    @Inject @Any
    Instance<ConnectorNormaliser> connectorNormalisers;

    private Map<String, ConnectorNormaliser> normalisersByConnectorId = Map.of();

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

    @PostConstruct
    void buildNormaliserRegistry() {
        final Map<String, ConnectorNormaliser> map = new HashMap<>();
        for (final ConnectorNormaliser cn : connectorNormalisers) {
            final String id = cn.connectorId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException(
                        cn.getClass().getName() + ".connectorId() returned null or blank");
            }
            if (map.put(id, cn) != null) {
                throw new IllegalStateException(
                        "Duplicate ConnectorNormaliser for connectorId '" + id + "' — "
                        + "each connector must have at most one normaliser");
            }
        }
        normalisersByConnectorId = Collections.unmodifiableMap(map);
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
    public DeliveryGuarantee deliveryGuarantee() {
        return DeliveryGuarantee.AT_LEAST_ONCE;
    }

    @Override
    public InboundNormaliser normaliserFor(UUID channelId) {
        CacheEntry entry = cache.get(channelId);
        if (entry == null) {
            return null;
        }
        return normalisersByConnectorId.get(entry.inboundConnectorId());
    }

    @Override
    public void open(final ChannelRef channel, final Map<String, String> metadata) {
    }

    @Override
    public void close(final ChannelRef channel) {
        cache.remove(channel.id());
    }

    public void onChannelInitialised(@Observes final ChannelInitialisedEvent event) {
        UUID channelId = event.channelId();
        bindingStore.findByChannelId(channelId).ifPresentOrElse(binding -> {
            cache.put(channelId, new CacheEntry(
                    binding.inboundConnectorId(),
                    binding.externalKey(),
                    binding.outboundConnectorId(),
                    binding.outboundDestination()));
            gateway.deregisterBackend(channelId, BACKEND_ID);
            gateway.registerBackend(channelId, this, "human_participating");
        }, () -> {
        });
    }

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
        String correlationId = msg.metadata() != null
                ? msg.metadata().get("correlation-id") : null;
        gateway.receiveHumanMessage(
                new ChannelRef(channel.id(), channel.name()),
                new InboundHumanMessage(
                        msg.externalSenderId(),
                        msg.content(),
                        msg.receivedAt(),
                        msg.metadata(),
                        correlationId,
                        null));
    }

    private Optional<Channel> tryAutoCreate(InboundMessage msg, String lookupKey) {
        Optional<AutoChannelSpec> specOpt = autoChannelPolicy.onFirstContact(msg, lookupKey);
        if (specOpt.isEmpty()) {
            return Optional.empty();
        }
        AutoChannelSpec spec = specOpt.get();
        ChannelCreateRequest req = ChannelCreateRequest.builder(spec.channelName())
                .description(spec.description())
                .semantic(spec.semantic())
                .allowedTypes(spec.allowedTypes())
                .deniedTypes(spec.deniedTypes())
                .inboundConnectorId(msg.connectorId())
                .externalKey(lookupKey)
                .outboundConnectorId(spec.outboundConnectorId())
                .outboundDestination(spec.outboundDestination())
                .build();
        try {
            FindOrCreateResult result = channelService.findOrCreateWithBinding(req);
            if (result.wasCreated()) {
                meterRegistry.counter("inbound_channels_auto_created_total",
                        "connector_id", msg.connectorId()).increment();
            }
            Channel channel = result.channel();
            gateway.initChannel(channel.id(), new ChannelRef(channel.id(), channel.name()));
            return Optional.of(channel);
        } catch (PersistenceException ex) {
            if (isConcurrentInsert(ex)) {
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
            LOG.debugf("No cache entry for channel %s (%s) — not a connector-backed channel, skipping",
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

    double discardedCount(final String connectorId) {
        return meterRegistry.counter("inbound_messages_discarded_total",
                "connector_id", connectorId).count();
    }

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

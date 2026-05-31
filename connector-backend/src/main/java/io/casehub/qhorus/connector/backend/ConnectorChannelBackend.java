package io.casehub.qhorus.connector.backend;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundMessage;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
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

    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public ConnectorChannelBackend(
            final ChannelGateway gateway,
            final ChannelService channelService,
            final ChannelBindingStore bindingStore,
            final ConnectorService connectorService,
            final MeterRegistry meterRegistry) {
        this.gateway = gateway;
        this.channelService = channelService;
        this.bindingStore = bindingStore;
        this.connectorService = connectorService;
        this.meterRegistry = meterRegistry;
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

    public CompletionStage<Void> onInboundMessage(@ObservesAsync final InboundMessage msg) {
        String key = ConnectorKeyStrategy.deriveKey(msg);
        channelService.findByConnectorKey(msg.connectorId(), key).ifPresentOrElse(channel -> {
            ChannelRef ref = new ChannelRef(channel.id, channel.name);
            gateway.receiveHumanMessage(ref, new InboundHumanMessage(
                    msg.externalSenderId(),
                    msg.content(),
                    msg.receivedAt(),
                    msg.metadata(),
                    null,
                    null));
        }, () -> {
            LOG.warnf("No channel found for inbound message from connector=%s key=%s — discarding",
                    msg.connectorId(), key);
            meterRegistry.counter("inbound_messages_discarded_total",
                    "connector_id", msg.connectorId()).increment();
        });
        return CompletableFuture.completedFuture(null);
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

    private record CacheEntry(
            String inboundConnectorId,
            String externalKey,
            String outboundConnectorId,
            String outboundDestination) {}
}

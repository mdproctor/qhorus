package io.casehub.qhorus.connector.backend;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.casehub.connectors.ConnectorMeshBridge;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Qhorus implementation of {@link ConnectorMeshBridge}.
 *
 * <p>Activates by classpath presence — displaces {@code NoOpConnectorMeshBridge @DefaultBean}
 * automatically. Requires {@code casehub.qhorus.connector-backend.delivery-channel} to be
 * configured; when blank the bridge is a no-op.
 *
 * <p>Posts a {@code STATUS} message to the configured delivery channel after each successful
 * MCP-initiated connector delivery. The message type is STATUS (not EVENT) because STATUS is
 * the correct type for content-bearing informatory messages — it carries information without
 * opening an expectation of reply. See PP-20260608-054090.
 *
 * <p>Channel ID is cached per tenancy after the first lookup. Stale-cache behavior (channel
 * deleted and recreated) requires a process restart to clear.
 *
 * <p><strong>ACL requirement:</strong> STATUS dispatches go through the full
 * {@code MessageService.dispatch()} enforcement gate including writer ACL and rate limiter.
 * If the delivery channel has {@code allowedWriters} configured, either leave it null (open)
 * or include {@code "role:system"} — the synthetic role tag for {@link ActorType#SYSTEM}.
 * Without it, every {@code notifyDelivered} call is silently dropped (WARN logged only).
 *
 * <p>Do not configure a connector-backed channel (one with {@code ConnectorChannelBackend}
 * registered) as the delivery channel — STATUS notifications would be delivered externally
 * to the connector's outbound destination.
 */
@ApplicationScoped
public class ConnectorQhorusMeshBridge implements ConnectorMeshBridge {

    private static final Logger LOG = Logger.getLogger(ConnectorQhorusMeshBridge.class);

    // Package-private: allows @ConfigProperty injection at runtime and direct
    // field assignment in CDI-free unit tests (constructor does not carry config).
    @ConfigProperty(name = "casehub.qhorus.connector-backend.delivery-channel", defaultValue = "")
    String deliveryChannelName;

    private final ChannelService channelService;
    private final MessageService messageService;
    private final CurrentPrincipal currentPrincipal;
    private final ManagedExecutor executor;

    // Keyed by tenancyId — each tenant resolves to its own channel UUID for the same name.
    // computeIfAbsent does not cache null, so a missing channel is retried on every call.
    private final ConcurrentHashMap<String, UUID> channelIdCache = new ConcurrentHashMap<>();

    @Inject
    public ConnectorQhorusMeshBridge(
            final ChannelService channelService,
            final MessageService messageService,
            final CurrentPrincipal currentPrincipal,
            final ManagedExecutor executor) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.currentPrincipal = currentPrincipal;
        this.executor = executor;
    }

    @Override
    public void notifyDelivered(final String connectorId, final String destination, final String content) {
        try {
            if (deliveryChannelName.isBlank()) return;

            // Capture context synchronously on the calling (HTTP request) thread.
            // QhorusInboundCurrentPrincipal absorbs ContextNotActiveException internally —
            // no defensive catch needed here.
            final String tenancyId = currentPrincipal.tenancyId();

            final UUID channelId = channelIdCache.computeIfAbsent(tenancyId, tid ->
                    channelService.findByName(deliveryChannelName)
                            .map(ch -> ch.id)
                            .orElse(null));

            if (channelId == null) {
                LOG.warnf("ConnectorMeshBridge: delivery-channel '%s' not found for tenancy '%s' — no-op",
                        deliveryChannelName, tenancyId);
                return;
            }

            // Destination is intentionally excluded from content — it is either a credential
            // (Slack/Teams webhook URL) or PII (phone, email), neither of which belongs in
            // the immutable ledger.
            final String text = "Delivered via %s: %s".formatted(connectorId, content != null ? content : "");
            final String sender = "system:connector:" + connectorId;

            executor.execute(() -> {
                try {
                    messageService.dispatch(MessageDispatch.builder()
                            .channelId(channelId)
                            .sender(sender)
                            .type(MessageType.STATUS)
                            .content(text)
                            .actorType(ActorType.SYSTEM)
                            .tenancyId(tenancyId)
                            .build());
                } catch (final Exception e) {
                    LOG.warnf(e, "ConnectorMeshBridge: dispatch failed for channel '%s'", deliveryChannelName);
                }
            });
        } catch (final Exception e) {
            LOG.warnf(e, "ConnectorMeshBridge: setup failed — connector delivery still succeeded");
        }
    }

    /** Package-private test helper — clears the channel ID cache between test methods. */
    void clearCache() {
        channelIdCache.clear();
    }
}

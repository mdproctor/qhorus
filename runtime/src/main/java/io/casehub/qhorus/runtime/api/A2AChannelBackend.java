package io.casehub.qhorus.runtime.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.ActorTypeResolver;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Protocol bridge backend that registers A2A as a first-class gateway participant.
 *
 * <p>Handles inbound A2A messages by resolving the sender's actor type and
 * routing through {@link QhorusMcpTools#sendMessage} to get the full pipeline
 * (ledger, fanOut, commitment tracking). {@link #post} is the outbound hook
 * called by {@link ChannelGateway#fanOut} — currently a logging no-op, the
 * correct hook for future SSE streaming (casehubio/qhorus#147).
 *
 * <p>Registration is idempotent per channel — {@link #ensureRegistered} uses
 * a ConcurrentHashMap set so only the first caller wins the race.
 *
 * Refs #135
 */
@ApplicationScoped
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
public class A2AChannelBackend implements ChannelBackend {

    private static final Logger LOG = Logger.getLogger(A2AChannelBackend.class);

    private final Set<UUID> registeredChannels = ConcurrentHashMap.newKeySet();

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelGateway gateway;

    @Inject
    A2AActorResolver actorResolver;

    @Override
    public String backendId() {
        return "a2a";
    }

    @Override
    public ActorType actorType() {
        return ActorType.AGENT;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        // no-op — registration state managed by ensureRegistered
    }

    /**
     * Outbound hook called by {@link ChannelGateway#fanOut} after a message is persisted.
     * Currently a logging no-op — the correct hook for future SSE streaming (#147).
     */
    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.correlationId() != null) {
            LOG.debugf("A2A backend notified: channel=%s correlationId=%s type=%s",
                    channel.name(), message.correlationId(), message.senderActorType());
        }
    }

    @Override
    public void close(ChannelRef channel) {
        registeredChannels.remove(channel.id());
    }

    /**
     * Registers this backend on the channel if not already registered.
     * Thread-safe — uses ConcurrentHashMap add semantics; only one caller wins the race.
     *
     * @param channelId UUID of the channel
     * @param ref       ChannelRef for open() initialisation
     */
    public void ensureRegistered(UUID channelId, ChannelRef ref) {
        if (registeredChannels.add(channelId)) {
            open(ref, Map.of());
            gateway.registerBackend(channelId, this, "agent");
        }
    }

    /**
     * Processes an inbound A2A message: resolves actor type, builds structured sender,
     * and routes via {@link QhorusMcpTools#sendMessage} for the full pipeline
     * (type validation, rate limiting, ledger, fanOut, commitment tracking).
     *
     * <p>Message type mapping: {@code role:"agent"} → {@code "response"};
     * all other roles → {@code "query"}. SYSTEM-typed actors via A2A also receive
     * type {@code "query"} — this heuristic covers current A2A usage patterns;
     * richer mapping is tracked in casehubio/qhorus#148.
     *
     * @param channelName    Qhorus channel name (from A2A contextId)
     * @param role           A2A role string ("user", "agent", or custom)
     * @param textContent    extracted text content from A2A parts
     * @param taskId         A2A taskId used as correlationId; a new UUID is generated if null
     * @param metadata       A2A message metadata (may contain agentId, agentCardUrl)
     * @param actorTypeHeader value of x-qhorus-actor-type HTTP header (may be null)
     * @return the correlationId used for this message
     */
    public String receive(String channelName, String role, String textContent,
            String taskId, Map<String, String> metadata, String actorTypeHeader) {
        ActorType resolved = actorResolver.resolve(role, actorTypeHeader, metadata);
        String agentId = metadata.get("agentId");
        String sender = buildSender(resolved, agentId, role);
        String type = "agent".equals(role) ? "response" : "query";
        String correlationId = (taskId != null && !taskId.isBlank())
                ? taskId
                : UUID.randomUUID().toString();

        tools.sendMessage(channelName, sender, type, textContent,
                correlationId, null, null, null, null);
        return correlationId;
    }

    private String buildSender(ActorType resolved, String agentId, String role) {
        return switch (resolved) {
            case AGENT -> (agentId != null && ActorTypeResolver.resolve(agentId) == ActorType.AGENT)
                    ? agentId
                    : "agent";
            case HUMAN -> "human:" + (agentId != null ? agentId : role);
            case SYSTEM -> agentId != null ? agentId : "system";
        };
    }
}

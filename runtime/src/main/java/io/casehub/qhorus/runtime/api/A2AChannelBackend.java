package io.casehub.qhorus.runtime.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Protocol bridge backend that registers A2A as a first-class gateway participant.
 *
 * <p>Handles inbound A2A messages by resolving the sender's actor type and
 * calling {@link MessageService#dispatch} directly. Enforcement (paused check,
 * allowed_writers ACL, rate limiting, LAST_WRITE, fanOut) is applied by
 * {@code dispatch()} — no bypass. Artefact lifecycle is intentionally omitted:
 * the A2A protocol has no artefact-ref passing.
 *
 * <p>{@link #post} is the outbound hook called by {@link ChannelGateway#fanOut}
 * after message persistence. It dispatches to all SSE consumers registered for
 * the message's correlationId via {@link #registerStream}. Consumers own their
 * own lifecycle — they deregister themselves via {@link #deregisterStream} on
 * terminal events or send failures.
 *
 * <p>{@link #registeredChannels} tracks gateway-registered channels for idempotent
 * registration. {@link #sseStreams} is a separate registry for SSE consumers —
 * the two maps are independent.
 *
 * <p><strong>Thread safety:</strong> {@link #sseStreams} uses {@link ConcurrentHashMap}
 * with a {@link ConcurrentHashMap#newKeySet()} value. {@link #deregisterStream} uses
 * {@link ConcurrentHashMap#compute} for atomic empty-removal — prevents a TOCTOU race
 * where a new consumer added between isEmpty() and remove() would be orphaned.
 *
 * <p><strong>Known constraint:</strong> SSE subscriptions do not survive server restarts.
 * After restart, this backend is not re-registered via {@link ChannelInitialisedEvent}
 * (lazy registration is by design per ADR-0008). Clients must re-subscribe and poll
 * to recover missed events. The proper fix requires persisting A2A channel participation
 * — tracked separately.
 *
 * <p>Refs #135, #147.
 */
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
public class A2AChannelBackend implements ChannelBackend {

    private static final Logger LOG = Logger.getLogger(A2AChannelBackend.class);

    /** Tracks channels registered with the gateway (idempotent ensureRegistered). */
    private final Set<UUID> registeredChannels = ConcurrentHashMap.newKeySet();

    /**
     * SSE consumer registry, keyed by correlationId.
     * Each consumer is called on every {@link #post} for its correlationId.
     * Consumers deregister themselves on terminal events or send failures.
     */
    private final ConcurrentHashMap<UUID, Set<Consumer<OutboundMessage>>> sseStreams =
            new ConcurrentHashMap<>();

    @Inject
    ChannelGateway gateway;

    @Inject
    A2AActorResolver actorResolver;

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    MessageService messageService;

    @Override
    public String backendId() {
        return "a2a";
    }

    @Override
    public ActorType actorType() {
        return ActorType.AGENT;
    }

    @Override
    public void open(final ChannelRef channel, final Map<String, String> metadata) {
        // no-op — channel registration managed by ensureRegistered
    }

    /**
     * Outbound hook called by {@link ChannelGateway#fanOut} after a message is persisted.
     * Dispatches to all SSE consumers registered for the message's correlationId.
     *
     * <p>EVENT messages have {@code null} correlationId by protocol definition and are
     * silently ignored — they do not carry task-level semantic state.
     *
     * <p>Each consumer manages its own lifecycle. This method does not perform cleanup —
     * consumers call {@link #deregisterStream} when they encounter terminal events or
     * send failures.
     *
     * <p><strong>Timing:</strong> called on a virtual thread from within the enclosing
     * {@code @Transactional} dispatch — the DB transaction may not have committed yet
     * when consumers are notified. Clients must treat SSE events as triggers, not
     * consistency guarantees.
     */
    @Override
    public void post(final ChannelRef channel, final OutboundMessage message) {
        LOG.debugf("A2A backend notified: channel=%s correlationId=%s type=%s",
                channel.name(), message.correlationId(), message.type());
        if (message.correlationId() == null) {
            return; // EVENT messages — always null correlationId, no task-level state
        }
        final Set<Consumer<OutboundMessage>> consumers = sseStreams.get(message.correlationId());
        if (consumers == null || consumers.isEmpty()) {
            return;
        }
        // Snapshot the set before iterating — consumers may call deregisterStream()
        // which modifies the map via compute(). Per-consumer try-catch ensures one
        // bad consumer (broken pipe, uncaught exception) does not block the rest.
        for (final Consumer<OutboundMessage> consumer : Set.copyOf(consumers)) {
            try {
                consumer.accept(message);
            } catch (final Exception e) {
                LOG.debugf(e, "SSE consumer exception for correlationId %s", message.correlationId());
            }
        }
    }

    @Override
    public void close(final ChannelRef channel) {
        registeredChannels.remove(channel.id());
    }

    /**
     * Registers this backend on the channel if not already registered.
     * Thread-safe — ConcurrentHashMap.add() is atomic; only one caller wins the race.
     */
    public void ensureRegistered(final UUID channelId, final ChannelRef ref) {
        if (registeredChannels.add(channelId)) {
            open(ref, Map.of());
            gateway.registerBackend(channelId, this, "agent");
        }
    }

    /**
     * Registers a consumer to receive outbound messages for the given correlationId.
     * The consumer is called on the virtual thread that executes {@link ChannelGateway#fanOut}.
     *
     * <p>Multiple consumers may be registered per correlationId (e.g. multiple SSE connections).
     * The consumer is responsible for calling {@link #deregisterStream} when it no longer
     * needs notifications.
     */
    void registerStream(final UUID correlationId, final Consumer<OutboundMessage> consumer) {
        sseStreams.computeIfAbsent(correlationId, k -> ConcurrentHashMap.newKeySet())
                  .add(consumer);
    }

    /**
     * Removes a consumer from the SSE registry.
     *
     * <p>Uses {@link ConcurrentHashMap#compute} for atomic removal — prevents the TOCTOU
     * race where another thread adds a new consumer between the isEmpty() check and the
     * map entry removal, which would orphan the newly-added consumer.
     */
    void deregisterStream(final UUID correlationId, final Consumer<OutboundMessage> consumer) {
        sseStreams.compute(correlationId, (k, s) -> {
            if (s == null) return null;
            s.remove(consumer);
            return s.isEmpty() ? null : s;
        });
    }

    /**
     * Returns the number of SSE consumers registered for the given correlationId.
     * Package-private for test visibility — allows integration tests to synchronize
     * on registration without relying on timing.
     */
    int streamCount(final UUID correlationId) {
        final Set<Consumer<OutboundMessage>> s = sseStreams.get(correlationId);
        return s == null ? 0 : s.size();
    }

    /**
     * Processes an inbound A2A message: resolves actor type, builds structured sender,
     * and calls {@link MessageService#dispatch} directly.
     *
     * <p>Message type mapping: {@code role:"agent"} → {@code "response"};
     * all other roles → {@code "query"}. Richer mapping is tracked in qhorus#148.
     *
     * @param channelName     Qhorus channel name (from A2A contextId)
     * @param role            A2A role string ("user", "agent", or custom)
     * @param textContent     extracted text content from A2A parts
     * @param taskId          A2A taskId used as correlationId; a new UUID is generated if null
     * @param metadata        A2A message metadata (may contain agentId, agentCardUrl)
     * @param actorTypeHeader value of x-qhorus-actor-type HTTP header (may be null)
     * @return the correlationId used for this message
     */
    @Transactional
    public String receive(final String channelName, final String role, final String textContent,
            final String taskId, final Map<String, String> metadata, final String actorTypeHeader) {
        final ActorType resolved = actorResolver.resolve(role, actorTypeHeader, metadata);
        final String agentId = metadata.get("agentId");
        final String sender = buildSender(resolved, agentId, role);
        String type = "agent".equals(role) ? "response" : "query";
        final String correlationId = (taskId != null && !taskId.isBlank())
                ? taskId
                : UUID.randomUUID().toString();

        final Channel ch = channelService.findByName(channelName)
                                               .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        Long inReplyTo = null;
        if ("response".equals(type)) {
            inReplyTo = messageStore.scan(MessageQuery.builder()
                            .channelId(ch.id()).correlationId(correlationId).limit(1).build())
                    .stream().findFirst().map(Message::id).orElse(null);
            if (inReplyTo == null) {
                type = "query";
            }
        }
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id())
                .sender(sender)
                .type(MessageType.valueOf(type.toUpperCase()))
                .content(textContent)
                .correlationId(correlationId)
                .inReplyTo(inReplyTo)
                .actorType(resolved)
                .build());
        return correlationId;
    }

    private String buildSender(final ActorType resolved, final String agentId, final String role) {
        return switch (resolved) {
            case AGENT -> (agentId != null && ActorTypeResolver.resolve(agentId) == ActorType.AGENT)
                    ? agentId
                    : "agent";
            case HUMAN -> "human:" + (agentId != null ? agentId : role);
            case SYSTEM -> agentId != null ? agentId : "system";
        };
    }
}

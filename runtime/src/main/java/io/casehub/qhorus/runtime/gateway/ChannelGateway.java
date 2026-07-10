package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.casehub.qhorus.api.gateway.DeliveryGuarantee;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import java.util.Objects;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.message.Message;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ChannelGateway {

    private static final Logger LOG = Logger.getLogger(ChannelGateway.class);

    private final ConcurrentHashMap<UUID, List<BackendEntry>> registry = new ConcurrentHashMap<>();

    final AgentChannelBackend agentBackend;
    final InboundNormaliser normaliser;
    final MessageService messageService;
    final ChannelService channelService;
    final CrossTenantChannelStore crossTenantChannelStore;
    final Event<ChannelInitialisedEvent> channelInitialisedEvents;
    final DeliveryConfig deliveryConfig;
    final CrossTenantMessageStore crossTenantMessageStore;
    Supplier<Tracer> tracerInstance;
    QhorusTracingConfig tracingConfig;

    @Inject
    public ChannelGateway(AgentChannelBackend agentBackend,
                          InboundNormaliser normaliser,
                          MessageService messageService,
                          ChannelService channelService,
                          CrossTenantChannelStore crossTenantChannelStore,
                          Event<ChannelInitialisedEvent> channelInitialisedEvents,
                          DeliveryConfig deliveryConfig,
                          CrossTenantMessageStore crossTenantMessageStore,
                          Instance<Tracer> tracerInstance,
                          QhorusTracingConfig tracingConfig) {
        this.agentBackend = agentBackend;
        this.normaliser = normaliser;
        this.messageService = messageService;
        this.channelService = channelService;
        this.crossTenantChannelStore = crossTenantChannelStore;
        this.channelInitialisedEvents = channelInitialisedEvents;
        this.deliveryConfig = deliveryConfig;
        this.crossTenantMessageStore = crossTenantMessageStore;
        this.tracerInstance = tracerInstance.isResolvable() ? tracerInstance::get : null;
        this.tracingConfig = tracingConfig;
    }

    /**
     * Initialises the gateway registry entry for a channel and fires
     * {@link ChannelInitialisedEvent} so external backends can register.
     * Called by create_channel and by the startup hook. Fires with {@code recovered=false}.
     * Idempotent — {@link ConcurrentHashMap#computeIfAbsent} ensures the agent
     * backend is added only once, but the event fires on every call.
     */
    public void initChannel(UUID channelId, ChannelRef ref) {
        initChannel(channelId, ref, false);
    }

    /**
     * As {@link #initChannel(UUID, ChannelRef)} but passes the {@code recovered} flag
     * through to {@link ChannelInitialisedEvent}. Set {@code recovered=true} when called
     * during startup recovery so observers can distinguish first-init from re-registration.
     * Refs #183.
     */
    public void initChannel(UUID channelId, ChannelRef ref, boolean recovered) {
        registry.computeIfAbsent(channelId, id -> {
            List<BackendEntry> entries = Collections.synchronizedList(new ArrayList<>());
            agentBackend.open(ref, Map.of());
            entries.add(new BackendEntry(agentBackend, "agent", null));
            return entries;
        });
        channelInitialisedEvents.fire(new ChannelInitialisedEvent(channelId, ref.name(), recovered));
    }

    /**
     * Restores gateway registry entries for all persisted channels on application startup.
     * Fires {@link ChannelInitialisedEvent} with {@code recovered=true} for each channel,
     * giving external backends the opportunity to re-register without implementing their
     * own startup recovery.
     */
    void onStart(@Observes StartupEvent ev) {
        for (Channel ch : crossTenantChannelStore.listAll()) {
            try {
                initChannel(ch.id(), new ChannelRef(ch.id(), ch.name()), true);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to initialise gateway registry for channel %s (%s) on startup",
                        ch.id(), ch.name());
            }
        }
    }

    /** Called by delete_channel to clean up all backends. */
    public void closeChannel(UUID channelId, ChannelRef ref) {
        List<BackendEntry> entries = registry.remove(channelId);
        if (entries != null) {
            for (BackendEntry e : entries) {
                try {
                    e.backend().close(ref);
                } catch (Exception ex) {
                    LOG.errorf(ex, "Error closing backend %s on channel %s",
                            e.backend().backendId(), channelId);
                }
            }
        }
    }

    public void registerBackend(UUID channelId, ChannelBackend backend, String backendType) {
        List<BackendEntry> entries = registry.computeIfAbsent(channelId,
                id -> Collections.synchronizedList(new ArrayList<>()));
        InboundNormaliser backendNormaliser = (backend instanceof HumanParticipatingChannelBackend hb)
                ? hb.normaliserFor(channelId) : null;
        synchronized (entries) {
            // Dedup: same backendId already registered → no-op (idempotent re-registration)
            if (entries.stream().anyMatch(e -> backend.backendId().equals(e.backend().backendId()))) {
                return;
            }
            if ("human_participating".equals(backendType)) {
                entries.stream()
                        .filter(e -> "human_participating".equals(e.backendType()))
                        .findFirst()
                        .ifPresent(existing -> {
                            throw new DuplicateParticipatingBackendException(
                                    channelId.toString(), existing.backend().backendId());
                        });
            }
            entries.add(new BackendEntry(backend, backendType, backendNormaliser));
        }
    }

    public void deregisterBackend(UUID channelId, String backendId) {
        if ("qhorus-internal".equals(backendId)) {
            throw new IllegalArgumentException("Cannot deregister the qhorus-internal backend.");
        }
        List<BackendEntry> entries = registry.get(channelId);
        if (entries != null) {
            entries.removeIf(e -> backendId.equals(e.backend().backendId()));
        }
    }

    public List<BackendRegistration> listBackends(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return entries.stream()
                .map(e -> new BackendRegistration(
                        e.backend().backendId(),
                        e.backendType(),
                        e.backend().actorType()))
                .toList();
    }

    /**
     * Fan-out to external backends after MessageService has persisted the message.
     * Called by {@link io.casehub.qhorus.runtime.message.MessageService#dispatch} after persistence.
     * Does NOT call QhorusChannelBackend — persistence already happened.
     *
     * <p>When the delivery pump is enabled ({@code casehub.qhorus.delivery.enabled=true}),
     * backends declaring {@link DeliveryGuarantee#AT_LEAST_ONCE} are skipped — the pump
     * delivers to them after the transaction commits. When the pump is disabled, all backends
     * receive fire-and-forget delivery regardless of their declared guarantee (safe fallback,
     * R3-02).
     *
     * @param channelName the human-readable channel name — must not be null
     * @return {@code true} if at least one backend was skipped for tracked delivery
     */
    public boolean fanOut(UUID channelId, String channelName, OutboundMessage message) {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.fanOut() && tracerInstance != null) {
            Tracer tracer = tracerInstance.get();
            span = tracer.spanBuilder("qhorus.fanout")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            span.setAttribute("qhorus.channel.id", channelId.toString());
        }
        try {
            ChannelRef ref = new ChannelRef(channelId, Objects.requireNonNull(channelName, "channelName"));
            List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
            boolean hasTracked = false;
            int backendCount = 0;
            final boolean deliveryEnabled = deliveryConfig.enabled();
            final Span parentSpan = span;
            final io.opentelemetry.context.Context otelContext = io.opentelemetry.context.Context.current();

            for (BackendEntry entry : List.copyOf(entries)) {
                if (entry.backend() == agentBackend) continue;
                ChannelBackend backend = entry.backend();
                if (deliveryEnabled && backend.deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE) {
                    hasTracked = true;
                    continue;
                }
                backendCount++;
                Thread.ofVirtual().start(otelContext.wrap(() -> {
                    Span childSpan = null;
                    if (parentSpan != null) {
                        Tracer tracer = tracerInstance.get();
                        childSpan = tracer.spanBuilder("qhorus.fanout.backend")
                                .setSpanKind(SpanKind.INTERNAL)
                                .startSpan();
                        childSpan.setAttribute("qhorus.fanout.backend_id", backend.backendId());
                        childSpan.setAttribute("qhorus.fanout.delivery_guarantee",
                                backend.deliveryGuarantee().name());
                    }
                    try {
                        backend.post(ref, message);
                    } catch (Exception ex) {
                        if (childSpan != null) {
                            childSpan.setStatus(StatusCode.ERROR);
                            childSpan.recordException(ex);
                        }
                        LOG.errorf(ex, "Backend %s failed on fanOut to channel %s",
                                backend.backendId(), channelId);
                    } finally {
                        if (childSpan != null) childSpan.end();
                    }
                }));
            }

            if (span != null) {
                span.setAttribute("qhorus.fanout.backend_count", backendCount);
                span.setAttribute("qhorus.fanout.has_tracked", hasTracked);
            }
            return hasTracked;
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    /** Inbound from HumanParticipatingChannelBackend. */
    public void receiveHumanMessage(ChannelRef channel, InboundHumanMessage raw) {
        BackendEntry participatingEntry = registry.getOrDefault(channel.id(), List.of()).stream()
                .filter(e -> "human_participating".equals(e.backendType()))
                .filter(e -> e.normaliser() != null)
                .findFirst()
                .orElse(null);
        InboundNormaliser effective = (participatingEntry != null)
                ? participatingEntry.normaliser()
                : this.normaliser;
        String backendId = (participatingEntry != null)
                ? participatingEntry.backend().backendId()
                : "default";

        NormalisedMessage n = effective.normalise(channel, raw);
        // Uses canonical constructor to bypass builder protocol validation —
        // inbound human messages may carry reply types (DONE/RESPONSE/etc.) with inReplyTo
        // synthesised by the normaliser from human context.
        messageService.dispatch(new MessageDispatch(
                channel.id(),
                n.senderInstanceId(),
                n.type(),
                n.content(),
                n.correlationId(),
                n.inReplyTo(),
                n.artefactRefs(),
                n.target(),
                null, // subjectId
                null, // causedByEntryId
                ActorType.HUMAN,
                null,  // deadline
                null,  // telemetry
                null,  // tenancyId — resolved by MessageService from CurrentPrincipal
                null)); // topic — resolved by MessageDispatch.build() default

        // ── Normaliser telemetry EVENT (Refs #202) ────────────────────────────
        // Unconditional: volume bounded by human message rate; EVENTs excluded from check_messages.
        boolean metadataKeyUsed = isValidMessageTypeMetadata(
                raw.metadata() != null ? raw.metadata().get("message-type") : null);
        String telemetryContent = String.format(
                "{\"tool_name\":\"normaliser\",\"backend_id\":\"%s\","
                        + "\"inferred_type\":\"%s\",\"metadata_key_used\":%s,\"in_reply_to_present\":%s}",
                backendId.replace("\\", "\\\\").replace("\"", "\\\""),
                n.type().name(),
                metadataKeyUsed,
                n.inReplyTo() != null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("system:normaliser")
                .type(MessageType.EVENT)
                .telemetry(telemetryContent)
                .actorType(ActorType.SYSTEM)
                .build());
    }

    private static boolean isValidMessageTypeMetadata(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return MessageType.valueOf(value.toUpperCase()) != MessageType.HANDOFF;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Inbound from HumanObserverChannelBackend — always EVENT regardless of content. */
    public void receiveObserverSignal(ChannelRef channel, ObserverSignal signal) {
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("human:" + signal.externalSenderId())
                .type(MessageType.EVENT)
                .actorType(ActorType.HUMAN)
                .build());
    }

    /**
     * Returns a snapshot of registered backends for {@code channelId} that declare
     * {@link DeliveryGuarantee#AT_LEAST_ONCE}. Excludes the internal agent backend.
     * Used by {@code DeliveryService} to drive per-backend delivery tasks.
     *
     * <p>Takes a {@link List#copyOf} snapshot before filtering to prevent
     * {@link java.util.ConcurrentModificationException} from concurrent registration.
     */
    List<BackendEntry> trackedEntries(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return List.copyOf(entries).stream()
                .filter(e -> e.backend() != agentBackend)
                .filter(e -> e.backend().deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE)
                .toList();
    }

    /**
     * Delivers a message to local BEST_EFFORT backends after receiving a cross-node
     * broadcast notification. Reads the message and channel from the shared database,
     * lazy-initializes the channel if unknown, and dispatches via virtual threads.
     *
     * <p>Skips: agent backend (qhorus-internal), AT_LEAST_ONCE backends (delivery pump
     * handles them). Convention-restricted to broadcaster implementations only.
     *
     * <p>Refs #162.
     *
     * @param channelId the channel UUID
     * @param messageId the message primary key
     */
    public void deliverRemote(UUID channelId, Long messageId) {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.fanOut() && tracerInstance != null) {
            Tracer tracer = tracerInstance.get();
            span = tracer.spanBuilder("qhorus.delivery.remote")
                    .setNoParent()
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            span.setAttribute("qhorus.channel.id", channelId.toString());
            span.setAttribute("qhorus.delivery.message_id", messageId);
        }
        try {
            Message msg = crossTenantMessageStore.find(messageId).orElse(null);
            if (msg == null) {
                LOG.debugf("Remote delivery: message %d not found, skipping", messageId);
                return;
            }
            Channel ch = crossTenantChannelStore.findById(channelId).orElse(null);
            if (ch == null) {
                LOG.debugf("Remote delivery: channel %s not found, skipping", channelId);
                return;
            }

            // Lazy channel initialization: if this node has no registry entry,
            // initialize the channel so backends can register via ChannelInitialisedEvent
            // before delivery proceeds.
            if (!registry.containsKey(channelId)) {
                initChannel(channelId, new ChannelRef(channelId, ch.name()));
            }

            ChannelRef ref = new ChannelRef(channelId, ch.name());
            OutboundMessage outbound = new OutboundMessage(
                    UUID.randomUUID(),
                    msg.sender(),
                    msg.messageType(),
                    msg.content(),
                    msg.correlationId(),
                    msg.inReplyTo(),
                    msg.actorType());

            List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
            int backendCount = 0;
            final Span parentSpan = span;
            final io.opentelemetry.context.Context otelContext = io.opentelemetry.context.Context.current();

            for (BackendEntry entry : List.copyOf(entries)) {
                if (entry.backend() == agentBackend) continue;
                ChannelBackend backend = entry.backend();
                if (backend.deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE) {
                    continue; // pump handles these
                }
                backendCount++;
                Thread.ofVirtual().start(otelContext.wrap(() -> {
                    Span childSpan = null;
                    if (parentSpan != null) {
                        Tracer tracer = tracerInstance.get();
                        childSpan = tracer.spanBuilder("qhorus.delivery.remote.backend")
                                .setSpanKind(SpanKind.INTERNAL)
                                .startSpan();
                        childSpan.setAttribute("qhorus.delivery.backend_id", backend.backendId());
                        childSpan.setAttribute("qhorus.delivery.delivery_guarantee",
                                backend.deliveryGuarantee().name());
                    }
                    try {
                        backend.post(ref, outbound);
                    } catch (Exception ex) {
                        if (childSpan != null) {
                            childSpan.setStatus(StatusCode.ERROR);
                            childSpan.recordException(ex);
                        }
                        LOG.warnf("Remote delivery: backend %s failed on channel %s: %s",
                                backend.backendId(), channelId, ex.getMessage());
                    } finally {
                        if (childSpan != null) childSpan.end();
                    }
                }));
            }

            if (span != null) {
                span.setAttribute("qhorus.delivery.backend_count", backendCount);
            }
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    record BackendEntry(ChannelBackend backend, String backendType, InboundNormaliser normaliser) {}

    public record BackendRegistration(String backendId, String backendType, ActorType actorType) {}
}

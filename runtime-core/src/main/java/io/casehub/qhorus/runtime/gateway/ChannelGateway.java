package io.casehub.qhorus.runtime.gateway;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.AgentChannelBackend;
import io.casehub.qhorus.api.gateway.BackendRegistration;
import io.casehub.qhorus.api.gateway.BackendRegistry;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelClosedEvent;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.gateway.ObserverSignal;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.message.MessageService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ChannelGateway implements BackendRegistry {

    private static final Logger LOG = Logger.getLogger(ChannelGateway.class);
    final AgentChannelBackend agentBackend;
    final InboundNormaliser normaliser;
    final MessageService messageService;
    final ChannelService channelService;
    final CrossTenantChannelStore crossTenantChannelStore;
    final Consumer<ChannelInitialisedEvent> channelInitialisedConsumer;
    final Consumer<ChannelClosedEvent> channelClosedConsumer;
    final DeliveryConfig deliveryConfig;
    final CrossTenantMessageStore crossTenantMessageStore;
    final io.casehub.qhorus.runtime.channel.ChannelMembershipService membershipService;
    private final ConcurrentHashMap<UUID, List<BackendEntry>> registry = new ConcurrentHashMap<>();
    final Supplier<Tracer> tracerSupplier;
    QhorusTracingConfig tracingConfig;

    public ChannelGateway(AgentChannelBackend agentBackend,
                          InboundNormaliser normaliser,
                          MessageService messageService,
                          ChannelService channelService,
                          CrossTenantChannelStore crossTenantChannelStore,
                          Consumer<ChannelInitialisedEvent> channelInitialisedConsumer,
                          Consumer<ChannelClosedEvent> channelClosedConsumer,
                          DeliveryConfig deliveryConfig,
                          CrossTenantMessageStore crossTenantMessageStore,
                          io.casehub.qhorus.runtime.channel.ChannelMembershipService membershipService,
                          Supplier<Tracer> tracerSupplier,
                          QhorusTracingConfig tracingConfig) {
        this.agentBackend = agentBackend;
        this.normaliser = normaliser;
        this.messageService = messageService;
        this.channelService = channelService;
        this.crossTenantChannelStore = crossTenantChannelStore;
        this.channelInitialisedConsumer = channelInitialisedConsumer;
        this.channelClosedConsumer = channelClosedConsumer;
        this.deliveryConfig = deliveryConfig;
        this.crossTenantMessageStore = crossTenantMessageStore;
        this.membershipService = membershipService;
        this.tracerSupplier = tracerSupplier;
        this.tracingConfig = tracingConfig;
    }

    private static boolean isValidMessageTypeMetadata(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return MessageType.valueOf(value.toUpperCase()) != MessageType.HANDOFF;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void initChannel(UUID channelId, ChannelRef ref) {
        initChannel(channelId, ref, false);
    }

    public void initChannel(UUID channelId, ChannelRef ref, boolean recovered) {
        registry.computeIfAbsent(channelId, id -> {
            List<BackendEntry> entries = Collections.synchronizedList(new ArrayList<>());
            agentBackend.open(ref, Map.of());
            entries.add(new BackendEntry(agentBackend, "agent", null));
            return entries;
        });
        channelInitialisedConsumer.accept(new ChannelInitialisedEvent(channelId, ref.name(), recovered));
    }

    public void initAllChannels() {
        for (Channel ch : crossTenantChannelStore.listAll()) {
            try {
                initChannel(ch.id(), new ChannelRef(ch.id(), ch.name()), true);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to initialise gateway registry for channel %s (%s) on startup",
                        ch.id(), ch.name());
            }
        }
    }

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
        channelClosedConsumer.accept(new ChannelClosedEvent(channelId, ref.name()));
    }

    @Override
    public void registerBackend(UUID channelId, ChannelBackend backend, String backendType) {
        List<BackendEntry> entries = registry.computeIfAbsent(channelId,
                id -> Collections.synchronizedList(new ArrayList<>()));
        InboundNormaliser backendNormaliser = (backend instanceof HumanParticipatingChannelBackend hb)
                ? hb.normaliserFor(channelId) : null;
        synchronized (entries) {
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

    @Override
    public void deregisterBackend(UUID channelId, String backendId) {
        if ("qhorus-internal".equals(backendId)) {
            throw new IllegalArgumentException("Cannot deregister the qhorus-internal backend.");
        }
        List<BackendEntry> entries = registry.get(channelId);
        if (entries != null) {
            entries.removeIf(e -> backendId.equals(e.backend().backendId()));
        }
    }

    @Override
    public List<BackendRegistration> listBackends(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return entries.stream()
                .map(e -> new BackendRegistration(
                        e.backend().backendId(),
                        e.backendType(),
                        e.backend().actorType()))
                .toList();
    }

    public boolean fanOut(UUID channelId, String channelName, OutboundMessage message) {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.fanOut() && tracerSupplier != null) {
            Tracer tracer = tracerSupplier.get();
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
                        Tracer tracer = tracerSupplier.get();
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
        try {
            String tenancyId = crossTenantChannelStore.findById(channel.id())
                    .map(io.casehub.qhorus.api.channel.Channel::tenancyId)
                    .orElse(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
            membershipService.join(channel.id(), n.senderInstanceId(),
                    io.casehub.qhorus.api.channel.MemberRole.PARTICIPANT, tenancyId);
        } catch (Exception ex) {
            LOG.debugf("Auto-membership skipped for %s on channel %s: %s",
                    n.senderInstanceId(), channel.id(), ex.getMessage());
        }
        messageService.dispatch(new MessageDispatch(
                channel.id(),
                n.senderInstanceId(),
                n.type(),
                n.content(),
                n.payload(),
                n.correlationId(),
                n.inReplyTo(),
                n.artefactRefs(),
                n.target(),
                null, null,
                ActorType.HUMAN,
                null, null, null, null,
                null, false));

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

    public void receiveObserverSignal(ChannelRef channel, ObserverSignal signal) {
        try {
            String senderId = "human:" + signal.externalSenderId();
            String tenancyId = crossTenantChannelStore.findById(channel.id())
                    .map(io.casehub.qhorus.api.channel.Channel::tenancyId)
                    .orElse(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
            membershipService.join(channel.id(), senderId,
                    io.casehub.qhorus.api.channel.MemberRole.OBSERVER, tenancyId);
        } catch (Exception ex) {
            LOG.debugf("Auto-membership skipped for observer on channel %s: %s",
                    channel.id(), ex.getMessage());
        }
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("human:" + signal.externalSenderId())
                .type(MessageType.EVENT)
                .actorType(ActorType.HUMAN)
                .build());
    }

    public List<BackendEntry> trackedEntries(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return List.copyOf(entries).stream()
                .filter(e -> e.backend() != agentBackend)
                .filter(e -> e.backend().deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE)
                .toList();
    }

    public void deliverRemote(UUID channelId, Long messageId) {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.fanOut() && tracerSupplier != null) {
            Tracer tracer = tracerSupplier.get();
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

            if (!registry.containsKey(channelId)) {
                initChannel(channelId, new ChannelRef(channelId, ch.name()));
            }

            ChannelRef ref = new ChannelRef(channelId, ch.name());
            OutboundMessage outbound = new OutboundMessage(
                    UUID.randomUUID(), msg.id(), msg.sender(), msg.messageType(),
                    msg.content(), msg.payload(), msg.correlationId(), msg.inReplyTo(),
                    msg.actorType(), msg.artefactRefs(), msg.target(), msg.topic());

            List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
            int backendCount = 0;
            final Span parentSpan = span;
            final io.opentelemetry.context.Context otelContext = io.opentelemetry.context.Context.current();

            for (BackendEntry entry : List.copyOf(entries)) {
                if (entry.backend() == agentBackend) continue;
                ChannelBackend backend = entry.backend();
                if (backend.deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE) {
                    continue;
                }
                backendCount++;
                Thread.ofVirtual().start(otelContext.wrap(() -> {
                    Span childSpan = null;
                    if (parentSpan != null) {
                        Tracer tracer = tracerSupplier.get();
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

            messageService.dispatchClusterObservers(ch.name(), channelId, msg.tenancyId(), msg);

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

    public record BackendEntry(ChannelBackend backend, String backendType, InboundNormaliser normaliser) {}
}

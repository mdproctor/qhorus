package io.casehub.qhorus.runtime.message;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster.ChannelActivityEvent;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.channel.AllowedWritersPolicy;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;

import io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static jakarta.transaction.Status.STATUS_ACTIVE;
import static jakarta.transaction.Status.STATUS_COMMITTED;

public class MessageService implements ConsumerMessaging {

    private static final Logger LOG = Logger.getLogger(MessageService.class);

    @FunctionalInterface
    public interface ObserverCallback {
        void dispatch(String channelName, UUID channelId, String tenancyId, Message message);
    }

    @FunctionalInterface
    public interface LedgerRecorder {
        LedgerWriteOutcome record(MessageDispatch dispatch, Long messageId, UUID commitmentId,
                                  Instant occurredAt, RoutingBridge.RoutingOutcome routingOutcome);
    }

    private final ChannelService channelService;
    private final CrossTenantChannelStore crossTenantChannelStore;
    private final CurrentPrincipal currentPrincipal;
    private final MessageStore messageStore;
    private final CommitmentService commitmentService;
    private final MessageTypePolicy messageTypePolicy;
    private final AllowedWritersPolicy allowedWritersPolicy;
    private final RateLimiter rateLimiter;
    private final QhorusConfig config;
    private final ObligorTrustPolicy obligorTrustPolicy;
    private final TransactionSynchronizationRegistry tsr;
    private final InstanceService instanceService;
    private final DeliverySignalQueue deliverySignalQueue;
    private final TopicService topicService;
    private final CorrelationIntegrityChecker correlationIntegrityChecker;
    private final ProtocolRegistry protocolRegistry;
    private final io.casehub.qhorus.api.store.CommitmentStore commitmentStore;
    private final ChannelActivityBroadcaster broadcaster;
    private final Supplier<Tracer> tracerSupplier;
    private final QhorusTracingConfig tracingConfig;
    private final EnforcementExecutor enforcementExecutor;
    private final RoutingBridge routingBridge;
    private final ObserverCallback observerDispatcher;
    private final ObserverCallback clusterObserverDispatcher;
    private final LedgerRecorder ledgerRecorder;

    private ChannelGateway channelGateway;

    private static final Set<MessageType> RESOLUTION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE, MessageType.RESPONSE);


    protected MessageService() {
        this.channelService              = null;
        this.crossTenantChannelStore     = null;
        this.currentPrincipal            = null;
        this.messageStore                = null;
        this.commitmentService           = null;
        this.messageTypePolicy           = null;
        this.allowedWritersPolicy        = null;
        this.rateLimiter                 = null;
        this.config                      = null;
        this.obligorTrustPolicy          = null;
        this.tsr                         = null;
        this.instanceService             = null;
        this.deliverySignalQueue         = null;
        this.topicService                = null;
        this.correlationIntegrityChecker = null;
        this.protocolRegistry            = null;
        this.commitmentStore             = null;
        this.broadcaster                 = null;
        this.tracerSupplier              = null;
        this.tracingConfig               = null;
        this.enforcementExecutor         = null;
        this.routingBridge               = null;
        this.observerDispatcher          = null;
        this.clusterObserverDispatcher   = null;
        this.ledgerRecorder              = null;
    }

    public MessageService(ChannelService channelService,
                          CrossTenantChannelStore crossTenantChannelStore,
                          CurrentPrincipal currentPrincipal,
                          MessageStore messageStore,
                          CommitmentService commitmentService,
                          MessageTypePolicy messageTypePolicy,
                          RateLimiter rateLimiter,
                          QhorusConfig config,
                          ObligorTrustPolicy obligorTrustPolicy,
                          TransactionSynchronizationRegistry tsr,
                          InstanceService instanceService,
                          DeliverySignalQueue deliverySignalQueue,
                          TopicService topicService,
                          CorrelationIntegrityChecker correlationIntegrityChecker,
                          ProtocolRegistry protocolRegistry,
                          io.casehub.qhorus.api.store.CommitmentStore commitmentStore,
                          ChannelActivityBroadcaster broadcaster,
                          Supplier<Tracer> tracerSupplier,
                          QhorusTracingConfig tracingConfig,
                          EnforcementExecutor enforcementExecutor,
                          RoutingBridge routingBridge,
                          ObserverCallback observerDispatcher,
                          ObserverCallback clusterObserverDispatcher,
                          LedgerRecorder ledgerRecorder) {
        this.channelService = channelService;
        this.crossTenantChannelStore = crossTenantChannelStore;
        this.currentPrincipal = currentPrincipal;
        this.messageStore = messageStore;
        this.commitmentService = commitmentService;
        this.messageTypePolicy = messageTypePolicy;
        this.allowedWritersPolicy = new AllowedWritersPolicy();
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.obligorTrustPolicy = obligorTrustPolicy;
        this.tsr = tsr;
        this.instanceService = instanceService;
        this.deliverySignalQueue = deliverySignalQueue;
        this.topicService = topicService;
        this.correlationIntegrityChecker = correlationIntegrityChecker;
        this.protocolRegistry = protocolRegistry;
        this.commitmentStore = commitmentStore;
        this.broadcaster = broadcaster;
        this.tracerSupplier = tracerSupplier;
        this.tracingConfig = tracingConfig;
        this.enforcementExecutor = enforcementExecutor;
        this.routingBridge = routingBridge;
        this.observerDispatcher = observerDispatcher;
        this.clusterObserverDispatcher = clusterObserverDispatcher;
        this.ledgerRecorder = ledgerRecorder;
    }

    public void setChannelGateway(ChannelGateway channelGateway) {
        this.channelGateway = channelGateway;
    }

    @Transactional
    public DispatchResult dispatch(MessageDispatch dispatch) {
        final String effectiveTenancyId = dispatch.tenancyId() != null
                ? dispatch.tenancyId()
                : currentPrincipal.tenancyId();

        final Channel ch = crossTenantChannelStore.findById(dispatch.channelId()).orElse(null);

        if (ch != null && !effectiveTenancyId.equals(ch.tenancyId())) {
            throw new IllegalArgumentException(
                    "Cross-tenant dispatch rejected: caller tenant=" + effectiveTenancyId
                    + ", channel tenant=" + ch.tenancyId());
        }

        if (ch != null && ch.paused()) {
            throw new IllegalStateException(
                    "Channel '" + ch.name() + "' is paused — send_message blocked. Use resume_channel to re-enable.");
        }

        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.dispatch() && tracerSupplier != null) {
            span = tracerSupplier.get().spanBuilder("qhorus.dispatch")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        try (io.opentelemetry.context.Scope scope = span != null ? span.makeCurrent() : null) {
            if (span != null) {
                span.setAttribute("qhorus.message.type", dispatch.type().name());
                span.setAttribute("qhorus.message.sender", dispatch.sender());
                span.setAttribute("qhorus.actor.type", dispatch.actorType().name());
                if (dispatch.correlationId() != null) {
                    span.setAttribute("qhorus.message.correlation_id", dispatch.correlationId());
                }
                if (dispatch.target() != null) {
                    span.setAttribute("qhorus.message.target", dispatch.target());
                }
                if (ch != null) {
                    span.setAttribute("qhorus.channel.id", ch.id().toString());
                    span.setAttribute("qhorus.channel.name", ch.name());
                    span.setAttribute("qhorus.channel.semantic", ch.semantic().name());
                    span.setAttribute("qhorus.tenancy.id", effectiveTenancyId);
                }
            }

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String sender = dispatch.sender();
            final ActorType senderActorType = dispatch.actorType();
            if (!allowedWritersPolicy.isAllowedWriter(sender, ch.allowedWriters(), () -> {
                final List<String> tags = new ArrayList<>(
                        instanceService.findCapabilityTagsForInstance(sender));
                tags.add("role:" + senderActorType.name().toLowerCase());
                return tags;
            })) {
                throw new IllegalStateException(
                        "Sender '" + sender + "' is not permitted to write to channel '" + ch.name()
                                + "'. Channel has an allowed_writers ACL.");
            }
        }

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String rateLimitError = rateLimiter.check(
                    ch.id(), ch.name(), dispatch.sender(), ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
            if (rateLimitError != null) {
                throw new IllegalStateException(rateLimitError);
            }
        }

        String capabilityTag = dispatch.target() != null && dispatch.target().startsWith("role:")
                ? dispatch.target().substring("role:".length()) : null;
        RoutingBridge.RoutingOutcome routingOutcome = null;
        if (ch != null && dispatch.target() != null && dispatch.target().startsWith("role:")) {
            routingOutcome = routingBridge.resolve(dispatch, ch, effectiveTenancyId);
            if (routingOutcome != null) {
                dispatch = dispatch.withTarget(routingOutcome.resolvedTarget());
            }
        }

        if (ch != null && dispatch.type() == MessageType.COMMAND
                && dispatch.target() != null
                && !dispatch.target().contains(":")
                && !dispatch.sender().contains(":")) {
            if (!obligorTrustPolicy.permits(
                    new ObligorTrustContext(dispatch.target(), ch.id(), ch.name()))) {
                throw new IllegalStateException(
                        "COMMAND rejected: obligor '" + dispatch.target()
                        + "' did not meet the trust threshold");
            }
        }

        List<TaggedAdvisory> taggedAdvisories = new ArrayList<>();
        if (ch != null) {
            messageTypePolicy.validate(ch, dispatch.type());
            final String adv = messageTypePolicy.advisory(ch, dispatch.type());
            if (adv != null) {
                LOG.warn(adv);
                taggedAdvisories.add(new TaggedAdvisory("TYPE_POLICY", adv));
            }
        }

        if (ch != null) {
            List<String> correlationAdvisories = correlationIntegrityChecker.check(dispatch, ch.id());
            if (!correlationAdvisories.isEmpty()) {
                for (String ca : correlationAdvisories) {
                    LOG.warn(ca);
                    taggedAdvisories.add(new TaggedAdvisory("CORRELATION_INTEGRITY", ca));
                }
            }
        }

        if (ch != null && !ch.protocols().isEmpty()) {
            List<io.casehub.qhorus.api.spi.ChannelProtocol> activeProtocols =
                    protocolRegistry.forProtocols(ch.protocols());
            if (!activeProtocols.isEmpty()) {
                List<io.casehub.qhorus.api.message.MessageView> recent =
                        messageStore.findRecent(ch.id(), config.protocol().lookbackSize());
                List<io.casehub.qhorus.api.message.Commitment> activeCommitments =
                        commitmentStore.findOpenByChannelId(ch.id());
                io.casehub.qhorus.api.spi.ProtocolContext protocolCtx =
                        new io.casehub.qhorus.api.spi.ProtocolContext(
                                ch.id(), ch.name(), dispatch.type(), dispatch.sender(),
                                dispatch.correlationId(), ch.protocolParticipants(),
                                recent, activeCommitments);
                for (io.casehub.qhorus.api.spi.ChannelProtocol protocol : activeProtocols) {
                    List<String> violations = protocol.evaluate(protocolCtx);
                    for (String v : violations) {
                        LOG.warn(v);
                        taggedAdvisories.add(new TaggedAdvisory(protocol.protocolName(), v));
                    }
                }
            }
        }

        if (ch != null) {
            try {
                enforceIfRequired(ch, taggedAdvisories, dispatch.type(), dispatch.sender(), enforcementExecutor,
                        dispatch, effectiveTenancyId);
            } catch (io.casehub.qhorus.api.message.EnforcementBlockedException ebe) {
                if (span != null) {
                    span.addEvent("qhorus.enforcement.gate",
                            io.opentelemetry.api.common.Attributes.of(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("qhorus.enforcement.mode"),
                                    ebe.mode().name(),
                                    io.opentelemetry.api.common.AttributeKey.longKey("qhorus.enforcement.violation_count"),
                                    (long) ebe.violations().size(),
                                    io.opentelemetry.api.common.AttributeKey.stringKey("qhorus.enforcement.violation_sources"),
                                    String.join(",", ebe.violationSources())));
                }
                throw ebe;
            }
        }

        if (ch != null && ch.semantic() == ChannelSemantic.LAST_WRITE) {
            final Optional<Message> existingOpt = messageStore.findLastMessage(ch.id());
            if (existingOpt.isPresent()) {
                final Message last = existingOpt.get();
                if (last.sender().equals(dispatch.sender())) {
                    Message updated = last.toBuilder()
                            .content(dispatch.content())
                            .payload(dispatch.payload())
                            .messageType(dispatch.type())
                            .correlationId(dispatch.correlationId())
                            .inReplyTo(dispatch.inReplyTo())
                            .artefactRefs(dispatch.artefactRefs())
                            .target(dispatch.target())
                            .topic(dispatch.topic())
                            .actorType(dispatch.actorType())
                            .createdAt(Instant.now())
                            .version(last.version() + 1)
                            .build();
                    Message saved = messageStore.put(updated);
                    channelService.updateLastActivity(ch.id(), ch.tenancyId());

                    observerDispatcher.dispatch(ch.name(), ch.id(), ch.tenancyId(), saved);

                    rateLimiter.recordSend(ch.id(), dispatch.sender(),
                            ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                    try {
                        channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                                UUID.randomUUID(), saved.id(), dispatch.sender(), dispatch.type(), dispatch.content(),
                                dispatch.payload(), dispatch.correlationId(), dispatch.inReplyTo(),
                                dispatch.actorType(), dispatch.artefactRefs(), dispatch.target(), dispatch.topic()));
                    } catch (final Exception e) {
                        // fanOut failures are non-fatal
                    }
                    final UUID signalChannelId = ch.id();
                    final String signalChannelName = ch.name();
                    final Long signalMessageId = saved.id();
                    tsr.registerInterposedSynchronization(new Synchronization() {
                        @Override public void beforeCompletion() {}
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) {
                                deliverySignalQueue.signal(signalChannelId);
                                broadcaster.broadcast(new ChannelActivityEvent(
                                        signalChannelId, signalChannelName, signalMessageId));
                            }
                        }
                    });
                    return new DispatchResult(saved.id(), ch.id(), saved.sender(),
                            saved.messageType(), saved.correlationId(), saved.inReplyTo(),
                            saved.artefactRefs(), saved.target(),
                            null, null, null, 0, saved.correctsMessageId(),
                            taggedAdvisories.stream().map(TaggedAdvisory::message).toList());
                } else {
                    throw new IllegalStateException(
                            "LAST_WRITE channel '" + ch.name() + "' already has a message from '"
                                    + last.sender() + "'. Only the current writer may update this channel.");
                }
            }
        }

        final UUID commitmentId = (dispatch.correlationId() != null &&
                (dispatch.type() == MessageType.COMMAND || dispatch.type() == MessageType.QUERY
                 || dispatch.type() == MessageType.PROPOSE))
                ? UUID.randomUUID() : null;

        Message message = Message.builder()
                .channelId(dispatch.channelId())
                .sender(dispatch.sender())
                .messageType(dispatch.type())
                .actorType(dispatch.actorType())
                .content(dispatch.content())
                .payload(dispatch.payload())
                .correlationId(dispatch.correlationId())
                .inReplyTo(dispatch.inReplyTo())
                .artefactRefs(dispatch.artefactRefs())
                .target(dispatch.target())
                .topic(dispatch.topic())
                .deadline(dispatch.deadline())
                .tenancyId(effectiveTenancyId)
                .commitmentId(commitmentId)
                .correctsMessageId(dispatch.correctsMessageId())
                .retraction(dispatch.retraction())
                .build();
        Message saved = messageStore.put(message);

        topicService.ensureExists(dispatch.channelId(), dispatch.topic(), effectiveTenancyId);

        final Long messageId = saved.id();
        final UUID storedCommitmentId = saved.commitmentId();
        final Instant occurredAt = saved.createdAt() != null
                ? saved.createdAt() : Instant.now();

        if (dispatch.correlationId() != null) {
            switch (dispatch.type()) {
                case QUERY, COMMAND, PROPOSE -> {
                    Instant effectiveDeadline = saved.deadline();
                    if (effectiveDeadline == null && dispatch.type() == MessageType.QUERY) {
                        var defaultDl = config.commitment().defaultQueryDeadline();
                        if (defaultDl.isPresent()) {
                            effectiveDeadline = Instant.now().plus(defaultDl.get());
                        }
                    }
                    if (effectiveDeadline == null && dispatch.type() == MessageType.PROPOSE) {
                        var defaultDl = config.commitment().defaultProposeDeadline();
                        if (defaultDl.isPresent()) {
                            effectiveDeadline = Instant.now().plus(defaultDl.get());
                        }
                    }
                    commitmentService.open(
                            storedCommitmentId,
                            dispatch.correlationId(), dispatch.channelId(), dispatch.type(),
                            dispatch.sender(), dispatch.target(), effectiveDeadline,
                            effectiveTenancyId, capabilityTag);
                }
                case STATUS -> commitmentService.acknowledge(dispatch.correlationId());
                case DONE -> commitmentService.fulfill(dispatch.correlationId());
                case RESPONSE -> {
                    var commitment = commitmentStore.findByCorrelationId(dispatch.correlationId());
                    if (commitment.isPresent() && commitment.get().messageType() != MessageType.PROPOSE) {
                        commitmentService.fulfill(dispatch.correlationId());
                    }
                }
                case DECLINE -> commitmentService.decline(dispatch.correlationId());
                case FAILURE -> commitmentService.fail(dispatch.correlationId());
                case HANDOFF -> commitmentService.delegate(dispatch.correlationId(), dispatch.target());
                case EVENT -> { /* no commitment effect */ }
            }
        }

        int parentReplyCount = 0;
        if (dispatch.inReplyTo() != null) {
            final var parentMsg = messageStore.find(dispatch.inReplyTo());
            if (parentMsg.isPresent()) {
                Message parent = parentMsg.get();
                Message updatedParent = parent.toBuilder().replyCount(parent.replyCount() + 1).build();
                messageStore.put(updatedParent);
                parentReplyCount = updatedParent.replyCount();
            }
        }

        channelService.updateLastActivity(dispatch.channelId(), effectiveTenancyId);

        final MessageDispatch dispatchWithTenancy = dispatch.tenancyId() != null ? dispatch
                : new MessageDispatch(dispatch.channelId(), dispatch.sender(), dispatch.type(),
                        dispatch.content(), dispatch.payload(), dispatch.correlationId(), dispatch.inReplyTo(),
                        dispatch.artefactRefs(), dispatch.target(), dispatch.subjectId(),
                        dispatch.causedByEntryId(), dispatch.actorType(), dispatch.deadline(),
                        dispatch.telemetry(), effectiveTenancyId, dispatch.topic(),
                        dispatch.correctsMessageId(), dispatch.retraction());
        final LedgerWriteOutcome ledgerOutcome =
                ledgerRecorder.record(dispatchWithTenancy, messageId, storedCommitmentId, occurredAt, routingOutcome);

        observerDispatcher.dispatch(
                ch != null ? ch.name() : null, dispatch.channelId(),
                saved.tenancyId(), saved);

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            rateLimiter.recordSend(ch.id(), dispatch.sender(),
                    ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
        }

        if (ch != null) {
            boolean hasTracked = false;
            try {
                hasTracked = channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                        UUID.randomUUID(), saved.id(), dispatch.sender(), dispatch.type(), dispatch.content(),
                        dispatch.payload(), dispatch.correlationId(), dispatch.inReplyTo(),
                        dispatch.actorType(), dispatch.artefactRefs(), dispatch.target(), dispatch.topic()));
            } catch (final Exception e) {
                // fanOut failures are non-fatal
            }

            final boolean signalDelivery = hasTracked;
            final UUID signalChannelId = ch.id();
            final String signalChannelName = ch.name();
            if (tsr.getTransactionStatus() == STATUS_ACTIVE) {
                tsr.registerInterposedSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() {}
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            if (signalDelivery) {
                                deliverySignalQueue.signal(signalChannelId);
                            }
                            broadcaster.broadcast(new ChannelActivityEvent(
                                    signalChannelId, signalChannelName, messageId));
                        }
                    }
                });
            }
        }

        return new DispatchResult(
                messageId, dispatch.channelId(), dispatch.sender(), dispatch.type(),
                dispatch.correlationId(), dispatch.inReplyTo(), dispatch.artefactRefs(), dispatch.target(),
                ledgerOutcome.entryId(), ledgerOutcome.subjectId(), ledgerOutcome.causedByEntryId(),
                parentReplyCount, dispatch.correctsMessageId(),
                taggedAdvisories.stream().map(TaggedAdvisory::message).toList());
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    public void dispatchClusterObservers(String channelName, UUID channelId,
                                         String tenancyId, Message message) {
        clusterObserverDispatcher.dispatch(channelName, channelId, tenancyId, message);
    }

    public Optional<Message> findById(final Long id) {
        return messageStore.find(id);
    }

    public List<Message> pollAfter(final UUID channelId, final Long afterId, final int limit) {
        return pollAfter(channelId, afterId, limit, false);
    }

    public List<Message> pollAfter(final UUID channelId, final Long afterId, final int limit,
                                   final boolean includeEvents) {
        final MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    public List<Message> pollAfterBySender(final UUID channelId, final Long afterId, final int limit,
                                           final String sender) {
        return pollAfterBySender(channelId, afterId, limit, sender, false);
    }

    public List<Message> pollAfterBySender(final UUID channelId, final Long afterId, final int limit,
                                           final String sender, final boolean includeEvents) {
        final MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit)
                .sender(sender);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    public Optional<Message> findByCorrelationId(final String correlationId) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                .correlationId(correlationId).limit(1).build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Message> findAllByCorrelationId(final String correlationId) {
        return messageStore.scan(MessageQuery.builder()
                .correlationId(correlationId).build());
    }

    @Transactional
    public Optional<Message> findResponseByCorrelationId(final UUID channelId, final String correlationId) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                .channelId(channelId).correlationId(correlationId)
                .messageType(MessageType.RESPONSE).limit(1).build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Transactional
    public Optional<Message> findDoneByCorrelationId(final UUID channelId, final String correlationId) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                .channelId(channelId).correlationId(correlationId)
                .messageType(MessageType.DONE).limit(1).build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Message> history(UUID channelId, long afterId, int limit) {
        return pollAfter(channelId, afterId, limit);
    }

    @Override
    public List<Message> history(UUID channelId, long afterId, int limit, boolean includeEvents) {
        return pollAfter(channelId, afterId, limit, includeEvents);
    }

    @Override
    public List<Message> historyBySender(UUID channelId, long afterId, int limit,
                                         String sender, boolean includeEvents) {
        return pollAfterBySender(channelId, afterId, limit, sender, includeEvents);
    }

    static void enforceIfRequired(Channel ch, List<TaggedAdvisory> taggedAdvisories,
                                  MessageType type, String sender, EnforcementExecutor executor) {
        enforceIfRequired(ch, taggedAdvisories, type, sender, executor, null, null);
    }

    static void enforceIfRequired(Channel ch, List<TaggedAdvisory> taggedAdvisories,
                                  MessageType type, String sender, EnforcementExecutor executor,
                                  MessageDispatch dispatch, String tenancyId) {
        if (ch.enforcementMode() == null
            || ch.enforcementMode() == io.casehub.qhorus.api.channel.EnforcementMode.ADVISORY) {
            return;
        }
        if (type == MessageType.EVENT) { return; }
        if (sender.contains(":")) { return; }
        if (RESOLUTION_TYPES.contains(type)) { return; }
        if (taggedAdvisories.isEmpty()) { return; }

        List<TaggedAdvisory> enforceable = taggedAdvisories.stream()
                .filter(ta -> !ch.enforcementExclusions().contains(ta.source()))
                .toList();
        if (enforceable.isEmpty()) { return; }

        if (executor != null && dispatch != null) {
            try {
                executor.execute(ch, dispatch, enforceable, tenancyId);
            } catch (Exception e) {
                LOG.warnf(e, "Enforcement execution failed for channel '%s'", ch.name());
            }
        }

        throw new io.casehub.qhorus.api.message.EnforcementBlockedException(
                ch.enforcementMode(),
                enforceable.stream().map(TaggedAdvisory::source).distinct().toList(),
                enforceable.stream().map(TaggedAdvisory::message).toList());
    }
}

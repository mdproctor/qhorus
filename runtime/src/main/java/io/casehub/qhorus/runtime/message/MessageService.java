package io.casehub.qhorus.runtime.message;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster.ChannelActivityEvent;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
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
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static jakarta.transaction.Status.STATUS_ACTIVE;
import static jakarta.transaction.Status.STATUS_COMMITTED;

@ApplicationScoped
public class MessageService implements MessageDispatcher {

    private static final Logger LOG = Logger.getLogger(MessageService.class);

    @Inject
    ChannelService channelService;

    @Inject
    CrossTenantChannelStore crossTenantChannelStore;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    MessageStore messageStore;

    @Inject
    CommitmentService commitmentService;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Inject @Any
    Instance<MessageObserver> observers;

    @Inject
    LedgerWriteService ledgerWriteService;

    @Inject
    AllowedWritersPolicy allowedWritersPolicy;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    QhorusConfig config;

    @Inject
    ObligorTrustPolicy obligorTrustPolicy;

    @Inject
    TransactionSynchronizationRegistry tsr;

    @Inject
    ChannelGateway channelGateway;

    @Inject
    InstanceService instanceService;

    @Inject
    DeliverySignalQueue deliverySignalQueue;
    @Inject
    TopicService        topicService;
    @Inject
    CorrelationIntegrityChecker correlationIntegrityChecker;
    @Inject
    io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry protocolRegistry;
    @Inject
    io.casehub.qhorus.api.store.CommitmentStore                 commitmentStore;


    @Inject
    ChannelActivityBroadcaster broadcaster;

    @Inject
    jakarta.enterprise.inject.Instance<io.opentelemetry.api.trace.Tracer> tracerInstance;

    @Inject
    io.casehub.qhorus.runtime.config.QhorusTracingConfig tracingConfig;

    @Transactional
    public DispatchResult dispatch(final MessageDispatch dispatch) {
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

        io.opentelemetry.api.trace.Span span = null;
        if (tracingConfig.enabled() && tracingConfig.dispatch() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.dispatch")
                    .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                    .startSpan();
        }
        try (io.opentelemetry.context.Scope scope = span != null ? span.makeCurrent() : null) {
            // Set span attributes: message-level first (always available), then channel-level (if resolved)
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
            if (!allowedWritersPolicy.isAllowedWriter(sender, ch.allowedWriters(), () -> {
                final List<String> tags = new ArrayList<>(
                        instanceService.findCapabilityTagsForInstance(sender));
                tags.add("role:" + dispatch.actorType().name().toLowerCase());
                return tags;
            })) {
                throw new IllegalStateException(
                        "Sender '" + sender + "' is not permitted to write to channel '" + ch.name()
                                + "'. Channel has an allowed_writers ACL.");
            }
            if (span != null) {
                span.addEvent("qhorus.enforcement.acl");
            }
        }

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String rateLimitError = rateLimiter.check(
                    ch.id(), ch.name(), dispatch.sender(), ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
            if (rateLimitError != null) {
                throw new IllegalStateException(rateLimitError);
            }
            if (span != null) {
                span.addEvent("qhorus.enforcement.rate_limit");
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
            if (span != null) {
                span.addEvent("qhorus.enforcement.trust");
            }
        }

        List<String> advisories = List.of();
        if (ch != null) {
            messageTypePolicy.validate(ch, dispatch.type());
            final String adv = messageTypePolicy.advisory(ch, dispatch.type());
            if (adv != null) {
                LOG.warn(adv);
                advisories = List.of(adv);
            }
            if (span != null) {
                span.addEvent("qhorus.enforcement.type_policy");
            }
        }

        if (ch != null) {
            List<String> correlationAdvisories = correlationIntegrityChecker.check(dispatch, ch.id());
            if (!correlationAdvisories.isEmpty()) {
                for (String ca : correlationAdvisories) {
                    LOG.warn(ca);
                }
                advisories = new ArrayList<>(advisories);
                advisories.addAll(correlationAdvisories);
            }
            if (span != null) {
                span.addEvent("qhorus.enforcement.correlation_integrity");
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
                    for (String v : violations) { LOG.warn(v); }
                    if (!violations.isEmpty()) {
                        advisories = new ArrayList<>(advisories);
                        advisories.addAll(violations);
                    }
                }
            }
            if (span != null) {
                span.addEvent("qhorus.enforcement.protocol");
            }
        }

        // LAST_WRITE overwrite path
        if (ch != null && ch.semantic() == ChannelSemantic.LAST_WRITE) {
            final Optional<Message> existingOpt = messageStore.findLastMessage(ch.id());
            if (existingOpt.isPresent()) {
                final Message last = existingOpt.get();
                if (last.sender().equals(dispatch.sender())) {
                    Message updated = last.toBuilder()
                            .content(dispatch.content())
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

                    MessageObserverDispatcher.dispatch(
                            ch.name(), ch.id(), ch.tenancyId(),
                            saved, observers.handles(), tsr);

                    rateLimiter.recordSend(ch.id(), dispatch.sender(),
                            ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                    try {
                        channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                                UUID.randomUUID(), saved.id(), dispatch.sender(), dispatch.type(), dispatch.content(),
                                dispatch.correlationId(),
                                dispatch.inReplyTo(),
                                dispatch.actorType(),
                                dispatch.artefactRefs(),
                                dispatch.target()));
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
                            null, null, null, 0, advisories);
                } else {
                    throw new IllegalStateException(
                            "LAST_WRITE channel '" + ch.name() + "' already has a message from '"
                                    + last.sender() + "'. Only the current writer may update this channel.");
                }
            }
        }

        final UUID commitmentId = (dispatch.correlationId() != null &&
                (dispatch.type() == MessageType.COMMAND || dispatch.type() == MessageType.QUERY))
                ? UUID.randomUUID() : null;

        Message message = Message.builder()
                .channelId(dispatch.channelId())
                .sender(dispatch.sender())
                .messageType(dispatch.type())
                .actorType(dispatch.actorType())
                .content(dispatch.content())
                .correlationId(dispatch.correlationId())
                .inReplyTo(dispatch.inReplyTo())
                .artefactRefs(dispatch.artefactRefs())
                .target(dispatch.target())
                .topic(dispatch.topic())
                .deadline(dispatch.deadline())
                .tenancyId(effectiveTenancyId)
                .commitmentId(commitmentId)
                .build();
        Message saved = messageStore.put(message);

        topicService.ensureExists(dispatch.channelId(), dispatch.topic(), effectiveTenancyId);

        final Long messageId = saved.id();
        final UUID storedCommitmentId = saved.commitmentId();
        final Instant occurredAt = saved.createdAt() != null
                ? saved.createdAt() : Instant.now();

        if (dispatch.correlationId() != null) {
            switch (dispatch.type()) {
                case QUERY, COMMAND -> {
                    Instant effectiveDeadline = saved.deadline();
                    if (effectiveDeadline == null && dispatch.type() == MessageType.QUERY) {
                        var defaultDl = config.commitment().defaultQueryDeadline();
                        if (defaultDl.isPresent()) {
                            effectiveDeadline = Instant.now().plus(defaultDl.get());
                        }
                    }
                    commitmentService.open(
                            storedCommitmentId,
                            dispatch.correlationId(), dispatch.channelId(), dispatch.type(),
                            dispatch.sender(), dispatch.target(), effectiveDeadline);
                }
                case STATUS -> commitmentService.acknowledge(dispatch.correlationId());
                case RESPONSE, DONE -> commitmentService.fulfill(dispatch.correlationId());
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
                        dispatch.content(), dispatch.correlationId(), dispatch.inReplyTo(),
                        dispatch.artefactRefs(), dispatch.target(), dispatch.subjectId(),
                        dispatch.causedByEntryId(), dispatch.actorType(), dispatch.deadline(),
                        dispatch.telemetry(), effectiveTenancyId, dispatch.topic());
        final LedgerWriteOutcome ledgerOutcome =
                ledgerWriteService.record(dispatchWithTenancy, messageId, storedCommitmentId, occurredAt);

        MessageObserverDispatcher.dispatch(
                ch != null ? ch.name() : null, dispatch.channelId(),
                saved.tenancyId(),
                saved, observers.handles(), tsr);

        if (span != null) {
            span.addEvent("qhorus.observer.dispatch");
        }

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            rateLimiter.recordSend(ch.id(), dispatch.sender(),
                    ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
        }

        if (ch != null) {
            boolean hasTracked = false;
            try {
                hasTracked = channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                        UUID.randomUUID(), saved.id(), dispatch.sender(), dispatch.type(), dispatch.content(),
                        dispatch.correlationId(),
                        dispatch.inReplyTo(),
                        dispatch.actorType(),
                        dispatch.artefactRefs(),
                        dispatch.target()));
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
                messageId,
                dispatch.channelId(),
                dispatch.sender(),
                dispatch.type(),
                dispatch.correlationId(),
                dispatch.inReplyTo(),
                dispatch.artefactRefs(),
                dispatch.target(),
                ledgerOutcome.entryId(),
                ledgerOutcome.subjectId(),
                ledgerOutcome.causedByEntryId(),
                parentReplyCount, advisories);
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
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
        MessageObserverDispatcher.dispatchClusterOnly(
                channelName, channelId, tenancyId, message, observers.handles());
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
                                                              .correlationId(correlationId)
                                                              .limit(1)
                                                              .build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Message> findAllByCorrelationId(final String correlationId) {
        return messageStore.scan(MessageQuery.builder()
                .correlationId(correlationId)
                .build());
    }

    @Transactional
    public Optional<Message> findResponseByCorrelationId(final UUID channelId, final String correlationId) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                                                              .channelId(channelId)
                                                              .correlationId(correlationId)
                                                              .messageType(MessageType.RESPONSE)
                                                              .limit(1)
                                                              .build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Transactional
    public Optional<Message> findDoneByCorrelationId(final UUID channelId, final String correlationId) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                                                              .channelId(channelId)
                                                              .correlationId(correlationId)
                                                              .messageType(MessageType.DONE)
                                                              .limit(1)
                                                              .build());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

}

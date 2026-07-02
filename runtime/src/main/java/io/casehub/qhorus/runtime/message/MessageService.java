package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;

import static jakarta.transaction.Status.STATUS_COMMITTED;

import jakarta.enterprise.inject.Instance;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.AllowedWritersPolicy;
import jakarta.transaction.TransactionSynchronizationRegistry;

import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
public class MessageService {

    private static final Logger LOG = Logger.getLogger(MessageService.class);

    @Inject
    ChannelService channelService;

    @Inject @CrossTenant
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
        }

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String rateLimitError = rateLimiter.check(
                    ch.id(), ch.name(), dispatch.sender(), ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
            if (rateLimitError != null) {
                throw new IllegalStateException(rateLimitError);
            }
        }

        if (ch != null && dispatch.type() == MessageType.COMMAND
                && dispatch.target() != null
                && !dispatch.target().contains(":")) {
            if (!obligorTrustPolicy.permits(
                    new ObligorTrustContext(dispatch.target(), ch.id(), ch.name()))) {
                throw new IllegalStateException(
                        "COMMAND rejected: obligor '" + dispatch.target()
                        + "' did not meet the trust threshold");
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
        }

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
                            .artefactRefs(ArtefactRefParser.parse(dispatch.artefactRefs()))
                            .target(dispatch.target())
                            .actorType(dispatch.actorType())
                            .createdAt(Instant.now())
                            .version(last.version() + 1)
                            .build();
                    Message saved = messageStore.put(updated);
                    channelService.updateLastActivity(ch.id(), ch.tenancyId());
                    rateLimiter.recordSend(ch.id(), dispatch.sender(),
                            ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                    final UUID signalChannelId = ch.id();
                    tsr.registerInterposedSynchronization(new Synchronization() {
                        @Override public void beforeCompletion() {}
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) {
                                deliverySignalQueue.signal(signalChannelId);
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
                .artefactRefs(ArtefactRefParser.parse(dispatch.artefactRefs()))
                .target(dispatch.target())
                .deadline(dispatch.deadline())
                .tenancyId(effectiveTenancyId)
                .commitmentId(commitmentId)
                .build();
        Message saved = messageStore.put(message);

        final Long messageId = saved.id();
        final UUID storedCommitmentId = saved.commitmentId();
        final Instant occurredAt = saved.createdAt() != null
                ? saved.createdAt() : Instant.now();

        if (dispatch.correlationId() != null) {
            switch (dispatch.type()) {
                case QUERY, COMMAND -> commitmentService.open(
                        storedCommitmentId,
                        dispatch.correlationId(), dispatch.channelId(), dispatch.type(),
                        dispatch.sender(), dispatch.target(), saved.deadline());
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
                        dispatch.telemetry(), effectiveTenancyId);
        final LedgerWriteOutcome ledgerOutcome =
                ledgerWriteService.record(dispatchWithTenancy, messageId, storedCommitmentId, occurredAt);

        MessageObserverDispatcher.dispatch(
                ch != null ? ch.name() : null, dispatch.channelId(),
                saved.tenancyId(),
                saved, observers.handles(), tsr);

        if (ch != null && dispatch.type() != MessageType.EVENT) {
            rateLimiter.recordSend(ch.id(), dispatch.sender(),
                    ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
        }

        if (ch != null) {
            try {
                final boolean hasTracked = channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                        UUID.randomUUID(), dispatch.sender(), dispatch.type(), dispatch.content(),
                        dispatch.correlationId() != null
                                ? UUID.fromString(dispatch.correlationId()) : null,
                        dispatch.inReplyTo(),
                        dispatch.actorType()));

                if (hasTracked) {
                    final UUID signalChannelId = ch.id();
                    tsr.registerInterposedSynchronization(new Synchronization() {
                        @Override public void beforeCompletion() {}
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) {
                                deliverySignalQueue.signal(signalChannelId);
                            }
                        }
                    });
                }
            } catch (final Exception e) {
                // fanOut failures are non-fatal
            }
        }

        return new DispatchResult(
                messageId,
                dispatch.channelId(),
                dispatch.sender(),
                dispatch.type(),
                dispatch.correlationId(),
                dispatch.inReplyTo(),
                ArtefactRefParser.parse(dispatch.artefactRefs()),
                dispatch.target(),
                ledgerOutcome.entryId(),
                ledgerOutcome.subjectId(),
                ledgerOutcome.causedByEntryId(),
                parentReplyCount, advisories);
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

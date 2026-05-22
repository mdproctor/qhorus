package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import jakarta.enterprise.inject.Instance;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

@ApplicationScoped
public class MessageService {

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    CommitmentService commitmentService;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Inject
    Instance<MessageObserver> observers;

    @Inject
    LedgerWriteService ledgerWriteService;

    @Transactional
    public DispatchResult dispatch(final MessageDispatch dispatch) {
        final Channel ch = channelService.findById(dispatch.channelId()).orElse(null);
        if (ch != null) messageTypePolicy.validate(ch, dispatch.type());

        // Generate commitmentId before persisting so it lands on the message entity
        final UUID commitmentId = (dispatch.correlationId() != null &&
                (dispatch.type() == MessageType.COMMAND || dispatch.type() == MessageType.QUERY))
                ? UUID.randomUUID() : null;

        final Message message = new Message();
        message.channelId = dispatch.channelId();
        message.sender = dispatch.sender();
        message.messageType = dispatch.type();
        message.actorType = dispatch.actorType();
        message.content = dispatch.content();
        message.correlationId = dispatch.correlationId();
        message.inReplyTo = dispatch.inReplyTo();
        message.artefactRefs = dispatch.artefactRefs();
        message.target = dispatch.target();
        message.commitmentId = commitmentId;
        messageStore.put(message);

        // Extract primitives BEFORE the REQUIRES_NEW boundary — no JPA entities cross it
        final Long messageId = message.id;
        final UUID storedCommitmentId = message.commitmentId;
        final Instant occurredAt = message.createdAt != null
                ? message.createdAt : Instant.now();

        // Commitment state machine
        if (dispatch.correlationId() != null) {
            switch (dispatch.type()) {
                case QUERY, COMMAND -> commitmentService.open(
                        storedCommitmentId,   // always non-null here — generated before put()
                        dispatch.correlationId(), dispatch.channelId(), dispatch.type(),
                        dispatch.sender(), dispatch.target(), message.deadline);
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
            var parentMsg = messageStore.find(dispatch.inReplyTo());
            if (parentMsg.isPresent()) {
                parentMsg.get().replyCount++;
                parentReplyCount = parentMsg.get().replyCount;
            }
        }

        channelService.updateLastActivity(dispatch.channelId());

        // Ledger write (REQUIRES_NEW — commits independently; failure propagates and rolls back outer tx)
        final LedgerWriteOutcome ledgerOutcome =
                ledgerWriteService.record(dispatch, messageId, storedCommitmentId, occurredAt);

        // Observer fan-out (after ledger write for ordering consistency)
        MessageObserverDispatcher.dispatch(
                ch != null ? ch.name : null, dispatch.channelId(), message, observers.handles());

        return new DispatchResult(
                messageId,
                dispatch.channelId(),
                dispatch.sender(),
                dispatch.type(),
                dispatch.correlationId(),
                dispatch.inReplyTo(),
                DispatchResult.parseArtefactRefs(dispatch.artefactRefs()),
                dispatch.target(),
                ledgerOutcome.entryId(),
                ledgerOutcome.subjectId(),
                ledgerOutcome.causedByEntryId(),
                parentReplyCount);
    }

    public Optional<Message> findById(Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type
     * (observer-only — not delivered to agent context).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return pollAfter(channelId, afterId, limit, false);
    }

    /**
     * Returns messages in channel posted after {@code afterId}. If {@code includeEvents}
     * is true, EVENT messages are included (for read-only observer instances).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit, boolean includeEvents) {
        MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query — avoids the
     * post-limit filtering bug where messages are lost when limit < total results.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return pollAfterBySender(channelId, afterId, limit, sender, false);
    }

    /**
     * Like {@link #pollAfter(UUID, Long, int, boolean)} but also filters by sender.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender,
            boolean includeEvents) {
        MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit)
                .sender(sender);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    public Optional<Message> findByCorrelationId(String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }

    /** Returns all messages with the given correlation ID ordered by id ascending. */
    public List<Message> findAllByCorrelationId(String correlationId) {
        return Message.<Message> find("correlationId = ?1 ORDER BY id ASC", correlationId).list();
    }

    /**
     * Find a RESPONSE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a matching response has arrived.
     */
    @Transactional
    public Optional<Message> findResponseByCorrelationId(UUID channelId, String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.RESPONSE, correlationId)
                .firstResultOptional();
    }

    /**
     * Find a DONE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a COMMAND obligation has been discharged.
     */
    @Transactional
    public Optional<Message> findDoneByCorrelationId(UUID channelId, String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.DONE, correlationId)
                .firstResultOptional();
    }

}

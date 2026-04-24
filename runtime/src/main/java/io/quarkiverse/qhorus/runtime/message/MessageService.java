package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.PendingReplyStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

@ApplicationScoped
public class MessageService {

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    PendingReplyStore pendingReplyStore;

    @Inject
    CommitmentService commitmentService;

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo) {
        return send(channelId, sender, type, content, correlationId, inReplyTo, null, null);
    }

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs) {
        return send(channelId, sender, type, content, correlationId, inReplyTo, artefactRefs, null);
    }

    @Transactional
    public Message send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs, String target) {
        Message message = new Message();
        message.channelId = channelId;
        message.sender = sender;
        message.messageType = type;
        message.content = content;
        message.correlationId = correlationId;
        message.inReplyTo = inReplyTo;
        message.artefactRefs = artefactRefs;
        message.target = target;
        messageStore.put(message);

        // Trigger commitment state machine for obligation tracking
        if (message.correlationId != null) {
            switch (message.messageType) {
                case QUERY, COMMAND -> commitmentService.open(
                        message.commitmentId != null ? message.commitmentId : java.util.UUID.randomUUID(),
                        message.correlationId, message.channelId, message.messageType,
                        message.sender, message.target, message.deadline);
                case STATUS -> commitmentService.acknowledge(message.correlationId);
                case RESPONSE, DONE -> commitmentService.fulfill(message.correlationId);
                case DECLINE -> commitmentService.decline(message.correlationId);
                case FAILURE -> commitmentService.fail(message.correlationId);
                case HANDOFF -> commitmentService.delegate(message.correlationId, message.target);
                case EVENT -> {
                    /* no commitment effect */ }
            }
        }

        if (inReplyTo != null) {
            messageStore.find(inReplyTo).ifPresent(parent -> parent.replyCount++);
        }

        channelService.updateLastActivity(channelId);

        return message;
    }

    public Optional<Message> findById(Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type
     * (observer-only — not delivered to agent context).
     */
    public List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query — avoids the
     * post-limit filtering bug where messages are lost when limit < total results.
     */
    public List<Message> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .sender(sender)
                        .build());
    }

    public Optional<Message> findByCorrelationId(String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }

    /** Returns all messages with the given correlation ID ordered by id ascending. */
    public List<Message> findAllByCorrelationId(String correlationId) {
        return Message.<Message> find("correlationId = ?1 ORDER BY id ASC", correlationId).list();
    }

    /**
     * Register or update a PendingReply row for the given correlation ID.
     * Upserts — if a row already exists, updates expiresAt rather than inserting a duplicate.
     */
    @Transactional
    public void registerPendingReply(String correlationId, UUID channelId, UUID instanceId,
            Instant expiresAt) {
        pendingReplyStore.findByCorrelationId(correlationId).ifPresentOrElse(
                existing -> {
                    existing.expiresAt = expiresAt;
                    pendingReplyStore.save(existing);
                },
                () -> {
                    PendingReply pr = new PendingReply();
                    pr.correlationId = correlationId;
                    pr.channelId = channelId;
                    pr.instanceId = instanceId;
                    pr.expiresAt = expiresAt;
                    pendingReplyStore.save(pr);
                });
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

    /** Delete the PendingReply row for the given correlation ID (cleanup on match or timeout). */
    @Transactional
    public void deletePendingReply(String correlationId) {
        pendingReplyStore.deleteByCorrelationId(correlationId);
    }

    /** Returns true if a PendingReply row exists for the given correlation ID. Used for cancellation detection. */
    public boolean pendingReplyExists(String correlationId) {
        return pendingReplyStore.existsByCorrelationId(correlationId);
    }
}

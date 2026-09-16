package io.casehub.qhorus.graphql.messaging;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ReactionManager;
import io.casehub.qhorus.api.message.CancelWaitResult;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.DeleteMessageResult;
import io.casehub.qhorus.api.message.DispatchMessageRequest;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageReactions;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.message.ReactionGroup;
import io.casehub.qhorus.api.message.WaitResult;
import io.casehub.qhorus.api.spi.messaging.MessagingApi;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.MessageReader;
import io.casehub.qhorus.api.store.ReactionReader;
import io.casehub.qhorus.api.store.query.MessageQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MessagingService implements MessagingApi {

    private final ConsumerMessaging consumerMessaging;
    private final MessageReader messageReader;
    private final ReactionReader reactionReader;
    private final MessageDispatcher messageDispatcher;
    private final CurrentPrincipal currentPrincipal;
    private final ReactionManager reactionManager;
    private final MessageStore messageStore;
    private final CommitmentStore commitmentStore;

    public MessagingService(ConsumerMessaging consumerMessaging,
                            MessageReader messageReader,
                            ReactionReader reactionReader,
                            MessageDispatcher messageDispatcher,
                            CurrentPrincipal currentPrincipal,
                            ReactionManager reactionManager,
                            MessageStore messageStore,
                            CommitmentStore commitmentStore) {
        this.consumerMessaging = consumerMessaging;
        this.messageReader = messageReader;
        this.reactionReader = reactionReader;
        this.messageDispatcher = messageDispatcher;
        this.currentPrincipal = currentPrincipal;
        this.reactionManager = reactionManager;
        this.messageStore = messageStore;
        this.commitmentStore = commitmentStore;
    }

    @Override
    public Message message(Long id) {
        return consumerMessaging.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + id));
    }

    @Override
    public List<Message> replies(Long messageId, Long afterId, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        MessageQuery.Builder builder = MessageQuery.builder().inReplyTo(messageId).limit(effectiveLimit);
        if (afterId != null) builder.afterId(afterId);
        return messageReader.scan(builder.build());
    }

    @Override
    public List<Message> searchMessages(String query, UUID channelId, Integer limit) {
        int pageSize = (limit != null && limit > 0) ? limit : 20;
        MessageQuery.Builder builder = MessageQuery.builder()
                .contentPattern(query)
                .excludeTypes(List.of(MessageType.EVENT))
                .limit(pageSize);
        if (channelId != null) builder.channelId(channelId);
        return messageReader.scan(builder.build());
    }

    @Override
    public List<ReactionGroup> reactions(Long messageId) {
        return groupReactions(reactionReader.findByMessage(messageId));
    }

    @Override
    public List<MessageReactions> reactionsBatch(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("messageIds must be non-null and non-empty");
        }
        if (messageIds.size() > 200) {
            throw new IllegalArgumentException("messageIds cannot exceed 200 entries");
        }
        Map<Long, List<Reaction>> byMessage = reactionReader.findByMessages(messageIds);
        return messageIds.stream()
                .map(id -> new MessageReactions(id,
                        groupReactions(byMessage.getOrDefault(id, List.of()))))
                .toList();
    }

    @Override
    public DispatchResult dispatchMessage(DispatchMessageRequest input) {
        String actorId = currentPrincipal.actorId();
        String tenancyId = currentPrincipal.tenancyId();

        MessageDispatch.Builder builder = MessageDispatch.builder()
                .channelId(input.channelId())
                .sender(actorId)
                .type(MessageType.valueOf(input.type()))
                .actorType(ActorType.HUMAN)
                .tenancyId(tenancyId);

        if (input.content() != null) builder.content(input.content());
        if (input.correlationId() != null) builder.correlationId(input.correlationId());
        if (input.inReplyTo() != null) builder.inReplyTo(input.inReplyTo());
        if (input.target() != null) builder.target(input.target());
        if (input.topic() != null) builder.topic(input.topic());
        if (input.deadline() != null) builder.deadline(input.deadline());

        return messageDispatcher.dispatch(builder.build());
    }

    @Override
    @Transactional
    public DeleteMessageResult deleteMessage(Long messageId) {
        Message msg = messageStore.find(messageId).orElse(null);
        if (msg == null) {
            return new DeleteMessageResult(messageId, false, null, null, null,
                    "Message not found: " + messageId);
        }
        String sender = msg.sender();
        String type = msg.messageType().name();
        String preview = msg.content() != null
                ? (msg.content().length() > 80 ? msg.content().substring(0, 80) + "…" : msg.content())
                : null;
        messageStore.scan(MessageQuery.builder().inReplyTo(messageId).build())
                .forEach(reply -> messageStore.put(reply.toBuilder().inReplyTo(null).build()));
        messageDispatcher.dispatch(MessageDispatch.builder()
                .channelId(msg.channelId()).sender("system").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build());
        messageStore.delete(msg.id());
        return new DeleteMessageResult(messageId, true, sender, type, preview,
                "Message " + messageId + " deleted");
    }

    @Override
    public Reaction react(Long messageId, String emoji) {
        return reactionManager.react(messageId, emoji);
    }

    @Override
    public boolean unreact(Long messageId, String emoji) {
        return reactionManager.unreact(messageId, emoji);
    }

    @Override
    public DispatchResult respondToApproval(String correlationId, String responseText, UUID channelId) {
        Long inReplyTo = consumerMessaging.findByCorrelationId(correlationId)
                .map(Message::id)
                .orElse(null);
        MessageDispatch dispatch = new MessageDispatch(
                channelId, "human", MessageType.RESPONSE,
                responseText, null, correlationId, inReplyTo, null, null, null, null,
                ActorType.HUMAN, null, null, null, null,
                null, false);
        return messageDispatcher.dispatch(dispatch);
    }

    @Override
    public CancelWaitResult cancelWait(String correlationId) {
        Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
        if (opt.isPresent()) {
            commitmentStore.deleteById(opt.get().id());
            return new CancelWaitResult(correlationId, true,
                    "Cancelled pending wait for correlation_id=" + correlationId);
        }
        return new CancelWaitResult(correlationId, false,
                "No pending wait found for correlation_id=" + correlationId);
    }

    @Override
    public WaitResult waitForReply(UUID channelId, String correlationId, Integer timeoutSeconds) {
        int timeout = timeoutSeconds != null ? timeoutSeconds : 90;
        Instant expiresAt = Instant.now().plusSeconds(timeout);

        long pollMs = 100;
        while (Instant.now().isBefore(expiresAt)) {
            Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
            if (opt.isEmpty()) {
                return new WaitResult(false, false, correlationId, null,
                        "Wait cancelled for correlation_id=" + correlationId);
            }
            Commitment commitment = opt.get();
            if (commitment.state() == CommitmentState.OPEN
                    || commitment.state() == CommitmentState.ACKNOWLEDGED
                    || commitment.state() == CommitmentState.FULFILLED
                    || commitment.state() == CommitmentState.DELEGATED) {
                Message response = findTerminalMessage(channelId, correlationId, MessageType.RESPONSE);
                if (response != null) {
                    return new WaitResult(true, false, correlationId, response,
                            "Response received for correlation_id=" + correlationId);
                }
                Message done = findTerminalMessage(channelId, correlationId, MessageType.DONE);
                if (done != null) {
                    return new WaitResult(true, false, correlationId, done,
                            "Done received for correlation_id=" + correlationId);
                }
            }
            if (commitment.state() == CommitmentState.DECLINED) {
                return new WaitResult(false, false, correlationId, null,
                        "Request was DECLINED for correlation_id=" + correlationId);
            }
            if (commitment.state() == CommitmentState.FAILED) {
                return new WaitResult(false, false, correlationId, null,
                        "Request FAILED for correlation_id=" + correlationId);
            }
            if (commitment.state() == CommitmentState.EXPIRED) {
                return new WaitResult(false, true, correlationId, null,
                        "Commitment EXPIRED for correlation_id=" + correlationId);
            }
            try {
                Thread.sleep(pollMs);
                pollMs = Math.min(pollMs * 2, 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new WaitResult(false, true, correlationId, null,
                "Timed out after " + timeout + "s waiting for response to correlation_id=" + correlationId);
    }

    @Override
    public WaitResult requestApproval(UUID channelId, String content, Integer timeoutSeconds) {
        String correlationId = UUID.randomUUID().toString();
        int timeout = timeoutSeconds != null ? timeoutSeconds : 300;
        String actorId = currentPrincipal.actorId();
        String tenancyId = currentPrincipal.tenancyId();

        messageDispatcher.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(actorId)
                .type(MessageType.QUERY)
                .content(content)
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .tenancyId(tenancyId)
                .build());

        return waitForReply(channelId, correlationId, timeout);
    }

    private List<ReactionGroup> groupReactions(Collection<Reaction> reactions) {
        return reactions.stream()
                .collect(Collectors.groupingBy(Reaction::emoji))
                .entrySet().stream()
                .map(e -> new ReactionGroup(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().map(Reaction::actorId).toList()))
                .toList();
    }

    private Message findTerminalMessage(UUID channelId, String correlationId, MessageType type) {
        List<Message> results = messageStore.scan(MessageQuery.builder()
                .channelId(channelId)
                .correlationId(correlationId)
                .messageType(type)
                .limit(1)
                .build());
        return results.isEmpty() ? null : results.getFirst();
    }
}

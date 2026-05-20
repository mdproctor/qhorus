package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.enterprise.inject.Instance;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveMessageService {

    @Inject
    ReactiveMessageStore messageStore;

    @Inject
    ReactiveChannelStore channelStore;

    @Inject
    CommitmentService commitmentService;

    @Inject
    Instance<MessageObserver> observers;

    public Uni<Message> send(UUID channelId, String sender, MessageType type, String content,
            String correlationId, Long inReplyTo, String artefactRefs, String target,
            ActorType actorType) {
        return Panache.withTransaction("qhorus", () -> {
            Message message = new Message();
            message.channelId = channelId;
            message.sender = sender;
            message.messageType = type;
            message.actorType = actorType;
            message.content = content;
            message.correlationId = correlationId;
            message.inReplyTo = inReplyTo;
            message.artefactRefs = artefactRefs;
            message.target = target;

            return channelStore.find(channelId)
                    .flatMap(chOpt -> {
                        final String channelName = chOpt.map(ch -> ch.name).orElse(null);
                        return messageStore.put(message)
                                .invoke(m -> MessageObserverDispatcher.dispatch(
                                        channelName, channelId, m, observers.handles()))
                                .invoke(m -> {
                                    // Trigger commitment state machine for obligation tracking
                                    if (m.correlationId != null) {
                                        switch (m.messageType) {
                                            case QUERY, COMMAND -> commitmentService.open(
                                                    m.commitmentId != null ? m.commitmentId
                                                            : java.util.UUID.randomUUID(),
                                                    m.correlationId, m.channelId, m.messageType,
                                                    m.sender, m.target, m.deadline);
                                            case STATUS -> commitmentService.acknowledge(m.correlationId);
                                            case RESPONSE, DONE -> commitmentService.fulfill(m.correlationId);
                                            case DECLINE -> commitmentService.decline(m.correlationId);
                                            case FAILURE -> commitmentService.fail(m.correlationId);
                                            case HANDOFF -> commitmentService.delegate(
                                                    m.correlationId, m.target);
                                            case EVENT -> { /* no commitment effect */ }
                                        }
                                    }
                                })
                                .flatMap(m -> inReplyTo != null
                                        ? messageStore.find(inReplyTo)
                                                .invoke(opt -> opt.ifPresent(parent -> parent.replyCount++))
                                                .map(ignored -> m)
                                        : Uni.createFrom().item(m))
                                .invoke(m -> chOpt.ifPresent(ch -> ch.lastActivityAt = Instant.now()));
                    });
        });
    }

    public Uni<Optional<Message>> findById(Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type.
     */
    public Uni<List<Message>> pollAfter(UUID channelId, Long afterId, int limit) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query.
     */
    public Uni<List<Message>> pollAfterBySender(UUID channelId, Long afterId, int limit, String sender) {
        return messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .sender(sender)
                        .build());
    }
}

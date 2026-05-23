package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.enterprise.inject.Instance;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
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

    /**
     * Dispatches a message to a channel via the reactive path.
     *
     * <p>Applies the paused check before persisting — throws {@link IllegalStateException}
     * if the channel is paused. Full enforcement parity (ACL, rate limit, type policy,
     * LAST_WRITE, ledger write, fanOut) is deferred to issue #193.
     *
     * <p>Returns {@code null} ledger fields ({@code ledgerEntryId},
     * {@code subjectId}, {@code causedByEntryId}) until #193 adds ledger writes
     * to the reactive path.
     */
    public Uni<DispatchResult> dispatch(final MessageDispatch dispatch) {
        return Panache.withTransaction("qhorus", () -> {
            final int[] replyCountHolder = { 0 };

            return channelStore.find(dispatch.channelId())
                    .flatMap(chOpt -> {
                        final String channelName = chOpt.map(ch -> ch.name).orElse(null);

                        // Paused check — moved here from QhorusDashboardService.sendHumanMessage().
                        // Full enforcement (ACL, rate limit, type policy) deferred to #193.
                        chOpt.ifPresent(ch -> {
                            if (ch.paused) {
                                throw new IllegalStateException(
                                        "Channel '" + ch.name
                                                + "' is paused — send_message blocked. Use resume_channel to re-enable.");
                            }
                        });

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
                        message.deadline = dispatch.deadline();
                        message.commitmentId = (dispatch.correlationId() != null &&
                                (dispatch.type() == MessageType.COMMAND
                                        || dispatch.type() == MessageType.QUERY))
                                ? UUID.randomUUID() : null;

                        return messageStore.put(message)
                                .invoke(m -> MessageObserverDispatcher.dispatch(
                                        channelName, dispatch.channelId(), m, observers.handles()))
                                .invoke(m -> {
                                    if (m.correlationId != null) {
                                        switch (m.messageType) {
                                            case QUERY, COMMAND -> commitmentService.open(
                                                    m.commitmentId,
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
                                .flatMap(m -> dispatch.inReplyTo() != null
                                        ? messageStore.find(dispatch.inReplyTo())
                                                .invoke(opt -> opt.ifPresent(parent -> {
                                                    parent.replyCount++;
                                                    replyCountHolder[0] = parent.replyCount;
                                                }))
                                                .map(ignored -> m)
                                        : Uni.createFrom().item(m))
                                .invoke(m -> chOpt.ifPresent(ch -> ch.lastActivityAt = Instant.now()))
                                .map(m -> new DispatchResult(
                                        m.id,
                                        dispatch.channelId(),
                                        dispatch.sender(),
                                        dispatch.type(),
                                        dispatch.correlationId(),
                                        dispatch.inReplyTo(),
                                        ArtefactRefParser.parse(dispatch.artefactRefs()),
                                        dispatch.target(),
                                        null,  // ledgerEntryId — deferred to #193
                                        null,  // subjectId — deferred to #193
                                        null,  // causedByEntryId — deferred to #193
                                        replyCountHolder[0]));
                    });
        });
    }

    public Uni<Optional<Message>> findById(final Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type.
     */
    public Uni<List<Message>> pollAfter(final UUID channelId, final Long afterId, final int limit) {
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
    public Uni<List<Message>> pollAfterBySender(final UUID channelId, final Long afterId,
            final int limit, final String sender) {
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

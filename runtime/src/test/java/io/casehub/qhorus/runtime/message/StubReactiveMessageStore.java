package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test stub satisfying the {@link ReactiveMessageStore} CDI dependency in
 * {@code @QuarkusTest} runs where {@code casehub.qhorus.reactive.enabled=true}
 * is set as a build property but no reactive JPA datasource is configured
 * (e.g. if a future test profile enables the reactive stack without PostgreSQL
 * DevServices).
 *
 * <p>All methods throw {@link UnsupportedOperationException}. No method is
 * expected to be called in tests that depend on this stub — those tests use
 * {@code ReactiveTestProfile} (PostgreSQL DevServices) which activates the real
 * {@code ReactiveJpaMessageStore} instead.
 *
 * <p>Pattern: {@code @DefaultBean} — displaced by {@code ReactiveJpaMessageStore}
 * or {@code InMemoryReactiveMessageStore} when either is on the classpath.
 */
@DefaultBean
@ApplicationScoped
class StubReactiveMessageStore implements ReactiveMessageStore {

    private static UnsupportedOperationException stub() {
        return new UnsupportedOperationException(
                "ReactiveMessageStore not available — stub only");
    }

    @Override public Uni<Message> put(final Message message)     { throw stub(); }
    @Override public Uni<Optional<Message>> find(final Long id)        { throw stub(); }
    @Override public Uni<List<Message>> scan(final MessageQuery query) { throw stub(); }
    @Override public Uni<Void> deleteAll(final UUID channelId)               { throw stub(); }
    @Override public Uni<Void> deleteNonEvent(final UUID channelId)          { throw stub(); }
    @Override public Uni<Void> delete(final Long id) { throw stub(); }
    @Override public Uni<Integer> countByChannel(final UUID channelId) { throw stub(); }
    @Override public Uni<Long> count(final MessageQuery query) { throw stub(); }
    @Override public Uni<Map<UUID, Long>> countAllByChannel() { throw stub(); }
    @Override public Uni<List<String>> distinctSendersByChannel(final UUID channelId,
                                                                 final MessageType excluded) { throw stub(); }
    @Override public Uni<Optional<Message>> findLastMessage(final UUID channelId) { throw stub(); }
    @Override public Multi<Message> stream(final MessageQuery query)              { throw stub(); }
    @Override public Uni<Integer> updateTopicName(final UUID channelId, final String oldTopic, final String newTopic) { throw stub(); }

    @Override
    public Uni<Integer> updateChannelId(UUID src, String topic, UUID tgt)                                             {throw stub();}


    @Override
    public Uni<List<io.casehub.qhorus.api.message.MessageView>> findRecentAsync(UUID channelId, int limit) {
        throw new UnsupportedOperationException("Stub");
    }
}

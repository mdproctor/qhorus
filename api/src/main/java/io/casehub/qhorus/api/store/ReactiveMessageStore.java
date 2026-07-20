package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ReactiveMessageStore {
    Uni<Message> put(Message message);

    Uni<Optional<Message>> find(Long id);

    Uni<List<Message>> scan(MessageQuery query);

    Uni<Void> deleteAll(UUID channelId);

    Uni<Void> deleteNonEvent(UUID channelId);

    Uni<Void> delete(Long id);

    Uni<Integer> countByChannel(UUID channelId);

    /**
     * Count messages matching the given query, wrapped in a {@code Uni}. Intentionally {@code long}
     * (Panache count semantics) unlike the legacy {@code int countByChannel}.
     */
    Uni<Long> count(MessageQuery query);

    Uni<Map<UUID, Long>> countAllByChannel();

    Uni<List<String>> distinctSendersByChannel(UUID channelId, MessageType excludedType);

    /**
     * Returns the most recent message in {@code channelId} by insertion order (highest id),
     * or empty if the channel has no messages.
     * Must be called within an active Hibernate Reactive session/transaction context.
     */
    Uni<Optional<Message>> findLastMessage(UUID channelId);

    /**
     * Streams messages matching {@code query} as a {@link Multi}, one message at a time.
     *
     * <p>Messages are emitted in ascending insertion order ({@code id ASC}).
     *
     * <p><strong>Current JPA implementation note:</strong> Quarkus 3.32 Hibernate Reactive
     * Panache does not expose a cursor-backed streaming API ({@code PanacheQuery.stream()}
     * does not exist at this version). {@code ReactiveJpaMessageStore.stream()} materialises
     * the full result list and emits items as a {@code Multi} — same memory profile as
     * {@link #scan}. The interface is the correct design; when Hibernate Reactive Panache
     * exposes cursor streaming, only the JPA implementation changes.
     *
     * <p>The in-memory testing implementation ({@code InMemoryReactiveMessageStore}) wraps
     * its in-memory list as {@code Multi.createFrom().iterable()} — functionally correct
     * for consumer unit tests.
     */
    Multi<Message> stream(MessageQuery query);

    Uni<Integer> updateTopicName(UUID channelId, String oldTopic, String newTopic);

    Uni<Integer> updateChannelId(UUID sourceChannelId, String topic, UUID targetChannelId);


    Uni<List<MessageView>> findRecentAsync(UUID channelId, int limit);
}

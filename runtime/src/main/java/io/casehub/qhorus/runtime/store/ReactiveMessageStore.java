package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveMessageStore {
    Uni<Message> put(Message message);

    Uni<Optional<Message>> find(Long id);

    Uni<List<Message>> scan(MessageQuery query);

    Uni<Void> deleteAll(UUID channelId);

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
}

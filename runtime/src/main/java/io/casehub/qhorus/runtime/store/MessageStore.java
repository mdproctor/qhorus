package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

public interface MessageStore {
    Message put(Message message);

    Optional<Message> find(Long id);

    List<Message> scan(MessageQuery query);

    void deleteAll(UUID channelId);

    void delete(Long id);

    int countByChannel(UUID channelId);

    /**
     * Count messages matching the given query. Intentionally {@code long}
     * (Panache count semantics) unlike the legacy {@code int countByChannel}.
     */
    long count(MessageQuery query);

    Map<UUID, Long> countAllByChannel();

    List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType);

    /**
     * Returns the most recent message in {@code channelId} by insertion order (highest id),
     * or {@link Optional#empty()} if the channel has no messages.
     * Used by LAST_WRITE semantics to check the current writer without bypassing the store seam.
     */
    Optional<Message> findLastMessage(UUID channelId);
}

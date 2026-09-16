package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.store.query.MessageQuery;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MessageReader {

    Optional<Message> find(Long id);

    List<Message> scan(MessageQuery query);

    int countByChannel(UUID channelId);

    long count(MessageQuery query);

    Map<UUID, Long> countAllByChannel();

    List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType);

    Optional<Message> findLastMessage(UUID channelId);

    List<MessageView> findRecent(UUID channelId, int limit);

    int countByCorrectsMessageId(Long messageId);
}

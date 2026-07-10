package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.Topic;

public interface TopicStore {
    Topic put(Topic topic);
    Optional<Topic> find(UUID channelId, String name);
    Optional<Topic> findById(Long id);
    List<Topic> findByChannel(UUID channelId);
    int rename(UUID channelId, String oldName, String newName);
    void delete(UUID channelId, String name);
    void deleteAll(UUID channelId);
}

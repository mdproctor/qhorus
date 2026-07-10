package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.UUID;

import io.casehub.qhorus.api.message.Topic;
import io.smallrye.mutiny.Uni;

public interface ReactiveTopicStore {
    Uni<Topic> put(Topic topic);
    Uni<Topic> find(UUID channelId, String name);
    Uni<Topic> findById(Long id);
    Uni<List<Topic>> findByChannel(UUID channelId);
    Uni<Integer> rename(UUID channelId, String oldName, String newName);
    Uni<Void> delete(UUID channelId, String name);
    Uni<Void> deleteAll(UUID channelId);
}

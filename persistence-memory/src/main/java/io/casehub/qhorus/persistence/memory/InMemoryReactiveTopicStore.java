package io.casehub.qhorus.persistence.memory;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.ReactiveTopicStore;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryReactiveTopicStore implements ReactiveTopicStore {

    private final InMemoryTopicStore delegate = new InMemoryTopicStore();

    @Override public Uni<Topic> put(Topic topic) { return Uni.createFrom().item(delegate.put(topic)); }
    @Override public Uni<Topic> find(UUID channelId, String name) { return Uni.createFrom().item(delegate.find(channelId, name).orElse(null)); }
    @Override public Uni<Topic> findById(Long id) { return Uni.createFrom().item(delegate.findById(id).orElse(null)); }
    @Override public Uni<List<Topic>> findByChannel(UUID channelId) { return Uni.createFrom().item(delegate.findByChannel(channelId)); }
    @Override public Uni<Integer> rename(UUID channelId, String oldName, String newName) { return Uni.createFrom().item(delegate.rename(channelId, oldName, newName)); }
    @Override public Uni<Void> delete(UUID channelId, String name) { delegate.delete(channelId, name); return Uni.createFrom().voidItem(); }
    @Override public Uni<Void> deleteAll(UUID channelId) { delegate.deleteAll(channelId); return Uni.createFrom().voidItem(); }
}

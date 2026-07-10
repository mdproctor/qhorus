package io.casehub.qhorus.persistence.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.TopicStore;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryTopicStore implements TopicStore {

    private final Map<Long, Topic> store = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    @Override
    public Topic put(Topic topic) {
        Optional<Topic> existing = find(topic.channelId(), topic.name());
        if (existing.isPresent()) {
            Topic updated = new Topic(existing.get().id(), topic.channelId(), existing.get().name(),
                    topic.resolved(), topic.resolvedAt(), topic.resolvedBy(),
                    existing.get().createdAt(), topic.tenancyId());
            store.put(updated.id(), updated);
            return updated;
        }
        Topic saved = new Topic(idCounter.getAndIncrement(), topic.channelId(), topic.name(),
                topic.resolved(), topic.resolvedAt(), topic.resolvedBy(),
                topic.createdAt(), topic.tenancyId());
        store.put(saved.id(), saved);
        return saved;
    }

    @Override
    public Optional<Topic> find(UUID channelId, String name) {
        return store.values().stream()
                .filter(t -> t.channelId().equals(channelId)
                        && t.name().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public Optional<Topic> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Topic> findByChannel(UUID channelId) {
        return store.values().stream()
                .filter(t -> t.channelId().equals(channelId))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    @Override
    public int rename(UUID channelId, String oldName, String newName) {
        Optional<Topic> existing = find(channelId, oldName);
        if (existing.isEmpty()) return 0;
        Topic t = existing.get();
        Topic renamed = new Topic(t.id(), t.channelId(), newName, t.resolved(),
                t.resolvedAt(), t.resolvedBy(), t.createdAt(), t.tenancyId());
        store.put(renamed.id(), renamed);
        return 1;
    }

    @Override
    public void delete(UUID channelId, String name) {
        store.values().removeIf(t -> t.channelId().equals(channelId)
                && t.name().equalsIgnoreCase(name));
    }

    @Override
    public void deleteAll(UUID channelId) {
        store.values().removeIf(t -> t.channelId().equals(channelId));
    }

    public void clear() {
        store.clear();
    }
}

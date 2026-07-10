package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.runtime.message.TopicEntity;

@ApplicationScoped
public class JpaTopicStore implements TopicStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    public Topic put(Topic topic) {
        String tenancyId = topic.tenancyId() != null ? topic.tenancyId() : currentPrincipal.tenancyId();
        Optional<TopicEntity> existing = TopicEntity.<TopicEntity>find(
                "channelId = ?1 AND LOWER(name) = LOWER(?2) AND tenancyId = ?3",
                topic.channelId(), topic.name(), tenancyId).firstResultOptional();
        if (existing.isPresent()) {
            TopicEntity e = existing.get();
            e.resolved = topic.resolved();
            e.resolvedAt = topic.resolvedAt();
            e.resolvedBy = topic.resolvedBy();
            return e.toDomain();
        }
        TopicEntity e = TopicEntity.fromDomain(topic);
        e.tenancyId = tenancyId;
        e.persist();
        return e.toDomain();
    }

    @Override
    public Optional<Topic> find(UUID channelId, String name) {
        return TopicEntity.<TopicEntity>find(
                "channelId = ?1 AND LOWER(name) = LOWER(?2) AND tenancyId = ?3",
                channelId, name, currentPrincipal.tenancyId())
                .firstResultOptional()
                .map(TopicEntity::toDomain);
    }

    @Override
    public Optional<Topic> findById(Long id) {
        return TopicEntity.<TopicEntity>findByIdOptional(id).map(TopicEntity::toDomain);
    }

    @Override
    public List<Topic> findByChannel(UUID channelId) {
        return TopicEntity.<TopicEntity>find(
                "channelId = ?1 AND tenancyId = ?2 ORDER BY createdAt",
                channelId, currentPrincipal.tenancyId())
                .list()
                .stream()
                .map(TopicEntity::toDomain)
                .toList();
    }

    @Override
    public int rename(UUID channelId, String oldName, String newName) {
        return (int) TopicEntity.update(
                "name = ?1 WHERE channelId = ?2 AND LOWER(name) = LOWER(?3) AND tenancyId = ?4",
                newName, channelId, oldName, currentPrincipal.tenancyId());
    }

    @Override
    public void delete(UUID channelId, String name) {
        TopicEntity.delete("channelId = ?1 AND LOWER(name) = LOWER(?2) AND tenancyId = ?3",
                channelId, name, currentPrincipal.tenancyId());
    }

    @Override
    public void deleteAll(UUID channelId) {
        TopicEntity.delete("channelId = ?1 AND tenancyId = ?2",
                channelId, currentPrincipal.tenancyId());
    }
}

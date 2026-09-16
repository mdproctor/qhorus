package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.runtime.message.TopicEntity;

@ApplicationScoped
public class JpaTopicStore implements TopicStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public Topic put(Topic topic) {
        String tenancyId = topic.tenancyId() != null ? topic.tenancyId() : currentPrincipal.tenancyId();
        Optional<TopicEntity> existing = em.createQuery("SELECT e FROM Topic e WHERE e.channelId = ?1 AND LOWER(e.name) = LOWER(?2) AND e.tenancyId = ?3", TopicEntity.class)
                .setParameter(1, topic.channelId()).setParameter(2, topic.name()).setParameter(3, tenancyId)
                .getResultStream().findFirst();
        if (existing.isPresent()) {
            TopicEntity e = existing.get();
            e.resolved = topic.resolved();
            e.resolvedAt = topic.resolvedAt();
            e.resolvedBy = topic.resolvedBy();
            return e.toDomain();
        }
        TopicEntity e = TopicEntity.fromDomain(topic);
        e.tenancyId = tenancyId;
        em.persist(e);
        return e.toDomain();
    }

    @Override
    public Optional<Topic> find(UUID channelId, String name) {
        return em.createQuery("SELECT e FROM Topic e WHERE e.channelId = ?1 AND LOWER(e.name) = LOWER(?2) AND e.tenancyId = ?3", TopicEntity.class)
                .setParameter(1, channelId).setParameter(2, name).setParameter(3, currentPrincipal.tenancyId())
                .getResultStream().findFirst()
                .map(TopicEntity::toDomain);
    }

    @Override
    public Optional<Topic> findById(Long id) {
        return Optional.ofNullable(em.find(TopicEntity.class, id)).map(TopicEntity::toDomain);
    }

    @Override
    public List<Topic> findByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM Topic e WHERE e.channelId = ?1 AND e.tenancyId = ?2 ORDER BY e.createdAt", TopicEntity.class)
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId())
                .getResultList()
                .stream()
                .map(TopicEntity::toDomain)
                .toList();
    }

    @Override
    public int rename(UUID channelId, String oldName, String newName) {
        return em.createQuery("UPDATE Topic e SET e.name = ?1 WHERE e.channelId = ?2 AND LOWER(e.name) = LOWER(?3) AND e.tenancyId = ?4")
                .setParameter(1, newName).setParameter(2, channelId).setParameter(3, oldName).setParameter(4, currentPrincipal.tenancyId())
                .executeUpdate();
    }

    @Override
    public void delete(UUID channelId, String name) {
        em.createQuery("DELETE FROM Topic e WHERE e.channelId = ?1 AND LOWER(e.name) = LOWER(?2) AND e.tenancyId = ?3")
                .setParameter(1, channelId).setParameter(2, name).setParameter(3, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    public void deleteAll(UUID channelId) {
        em.createQuery("DELETE FROM Topic e WHERE e.channelId = ?1 AND e.tenancyId = ?2")
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }
}

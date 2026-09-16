package io.casehub.qhorus.runtime.store.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.ReactionStore;
import io.casehub.qhorus.runtime.message.ReactionEntity;

@ApplicationScoped
public class JpaReactionStore implements ReactionStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public Reaction react(Long messageId, String emoji, String actorId, String tenancyId) {
        String tid = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
        Optional<ReactionEntity> existing = em.createQuery("SELECT e FROM Reaction e WHERE e.messageId = ?1 AND e.emoji = ?2 AND e.actorId = ?3", ReactionEntity.class)
                .setParameter(1, messageId).setParameter(2, emoji).setParameter(3, actorId)
                .getResultStream().findFirst();
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }
        ReactionEntity e = new ReactionEntity();
        e.messageId = messageId;
        e.emoji = emoji;
        e.actorId = actorId;
        e.tenancyId = tid;
        em.persist(e);
        return e.toDomain();
    }

    @Override
    public boolean unreact(Long messageId, String emoji, String actorId) {
        return em.createQuery("DELETE FROM Reaction e WHERE e.messageId = ?1 AND e.emoji = ?2 AND e.actorId = ?3")
                .setParameter(1, messageId).setParameter(2, emoji).setParameter(3, actorId).executeUpdate() > 0;
    }

    @Override
    public List<Reaction> findByMessage(Long messageId) {
        return em.createQuery("SELECT e FROM Reaction e WHERE e.messageId = ?1", ReactionEntity.class)
                .setParameter(1, messageId).getResultList().stream().map(ReactionEntity::toDomain).toList();
    }

    @Override
    public Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        List<ReactionEntity> entities = em.createQuery("SELECT e FROM Reaction e WHERE e.messageId IN ?1", ReactionEntity.class)
                .setParameter(1, List.copyOf(messageIds)).getResultList();
        Map<Long, List<Reaction>> result = new HashMap<>();
        for (Long id : messageIds) {
            result.put(id, entities.stream()
                    .filter(e -> e.messageId.equals(id))
                    .map(ReactionEntity::toDomain)
                    .toList());
        }
        return result;
    }

    @Override
    public void deleteByMessage(Long messageId) {
        em.createQuery("DELETE FROM Reaction e WHERE e.messageId = ?1").setParameter(1, messageId).executeUpdate();
    }

    @Override
    public void deleteByChannel(UUID channelId) {
        em.createQuery("DELETE FROM Reaction e WHERE e.messageId IN (SELECT m.id FROM Message m WHERE m.channelId = ?1)")
                .setParameter(1, channelId).executeUpdate();
    }
}

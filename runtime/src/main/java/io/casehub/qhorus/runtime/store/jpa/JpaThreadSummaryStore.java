package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.channel.ThreadSummary;
import io.casehub.qhorus.api.store.ThreadSummaryStore;
import io.casehub.qhorus.runtime.channel.ThreadSummaryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaThreadSummaryStore implements ThreadSummaryStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public ThreadSummary save(ThreadSummary summary) {
        Optional<ThreadSummaryEntity> existing = em.createQuery("SELECT e FROM ThreadSummary e WHERE e.channelId = ?1 AND e.correlationId = ?2", ThreadSummaryEntity.class)
                .setParameter(1, summary.channelId()).setParameter(2, summary.correlationId())
                .getResultStream().findFirst();

        ThreadSummaryEntity e = ThreadSummaryEntity.fromDomain(summary);
        if (existing.isPresent()) {
            e.id = existing.get().id;
            e = em.merge(e);
        } else {
            em.persist(e);
        }
        return e.toDomain();
    }

    @Override
    public Optional<ThreadSummary> findByCorrelationId(UUID channelId,
                                                        String correlationId) {
        return em.createQuery("SELECT e FROM ThreadSummary e WHERE e.channelId = ?1 AND e.correlationId = ?2", ThreadSummaryEntity.class)
                   .setParameter(1, channelId).setParameter(2, correlationId)
                   .getResultStream().findFirst()
                   .map(ThreadSummaryEntity::toDomain);
    }

    @Override
    public List<ThreadSummary> findByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM ThreadSummary e WHERE e.channelId = ?1", ThreadSummaryEntity.class)
                   .setParameter(1, channelId).getResultList().stream()
                   .map(ThreadSummaryEntity::toDomain)
                   .toList();
    }

    @Override
    @Transactional
    public void delete(UUID channelId, String correlationId) {
        em.createQuery("DELETE FROM ThreadSummary e WHERE e.channelId = ?1 AND e.correlationId = ?2")
                    .setParameter(1, channelId).setParameter(2, correlationId).executeUpdate();
    }
}

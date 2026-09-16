package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JpaCrossTenantCommitmentStore implements CrossTenantCommitmentStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;


    @Override
    public List<Commitment> findAllOpen() {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.state IN ?1 ORDER BY e.expiresAt ASC NULLS LAST", CommitmentEntity.class)
                .setParameter(1, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.channelId = ?1 AND e.state NOT IN ?2", CommitmentEntity.class)
                .setParameter(1, channelId).setParameter(2, terminalStates())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findAllByCorrelationId(String correlationId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.correlationId = ?1 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                           .setParameter(1, correlationId)
                           .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.obligor = ?1 AND e.state IN ?2 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                           .setParameter(1, obligor).setParameter(2, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                           .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public java.util.Optional<Commitment> findLatestDelegatedByObligor(String obligor) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.obligor = ?1 AND e.state = ?2 ORDER BY e.resolvedAt DESC", CommitmentEntity.class)
                           .setParameter(1, obligor).setParameter(2, CommitmentState.DELEGATED)
                           .getResultStream().findFirst()
                   .map(CommitmentEntity::toDomain);
    }

    @Override
    public long countOpenByObligor(String obligor) {
        return em.createQuery("SELECT COUNT(e) FROM Commitment e WHERE e.obligor = ?1 AND e.state IN ?2", Long.class)
                          .setParameter(1, obligor).setParameter(2, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED)).getSingleResult();
    }

    @Override
    public java.util.Map<String, Long> findObligorsExceedingCount(int minCount) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.state IN ?1", CommitmentEntity.class)
                        .setParameter(1, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                        .getResultList()
                .stream()
                .filter(c -> c.obligor != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.obligor, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() >= minCount)
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, java.util.Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }


    @Override
    @Transactional
    public void expireOverdue(Instant cutoff) {
        List<CommitmentEntity> overdue = em.createQuery("SELECT e FROM Commitment e WHERE e.expiresAt < ?1 AND e.state NOT IN ?2", CommitmentEntity.class)
                .setParameter(1, cutoff).setParameter(2, terminalStates()).getResultList();
        Instant now = Instant.now();
        overdue.forEach(c -> {
            c.state = CommitmentState.EXPIRED;
            c.resolvedAt = now;
            em.merge(c);
        });
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}

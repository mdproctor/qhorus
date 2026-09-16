package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaCommitmentStore implements CommitmentStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public Commitment save(Commitment commitment) {
        CommitmentEntity c = CommitmentEntity.fromDomain(commitment);
        if (c.id == null) {
            em.persist(c);
        } else {
            c = em.merge(c);
        }
        return c.toDomain();
    }

    @Override
    public Optional<Commitment> findById(UUID id) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.id = ?1 AND e.tenancyId = ?2", CommitmentEntity.class)
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId())
                .getResultStream().findFirst()
                .map(CommitmentEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Commitment> findByCorrelationId(String correlationId) {
        List<CommitmentEntity> all = em.createQuery("SELECT e FROM Commitment e WHERE e.correlationId = ?1 AND e.tenancyId = ?2 ORDER BY e.createdAt DESC", CommitmentEntity.class)
                .setParameter(1, correlationId).setParameter(2, currentPrincipal.tenancyId())
                .getResultList();
        Optional<CommitmentEntity> active = all.stream()
                .filter(c -> c.state.isActive())
                .findFirst();
        return active.or(() -> all.stream().findFirst())
                .map(CommitmentEntity::toDomain);
    }

    @Override
    public List<Commitment> findAllByCorrelationId(String correlationId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.correlationId = ?1 AND e.tenancyId = ?2 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                   .setParameter(1, correlationId).setParameter(2, currentPrincipal.tenancyId())
                   .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }


    @Override
    public List<Commitment> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return em.createQuery("SELECT e FROM Commitment e WHERE e.id IN ?1", CommitmentEntity.class)
                .setParameter(1, List.copyOf(ids))
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.obligor = ?1 AND e.channelId = ?2 AND e.state NOT IN ?3 AND e.tenancyId = ?4", CommitmentEntity.class)
                .setParameter(1, obligor).setParameter(2, channelId).setParameter(3, terminalStates()).setParameter(4, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }


    @Override
    public List<Commitment> findByObligorInTenancy(String obligor, String tenancyId) {
        if (obligor == null) {return List.of();}
        return em.createQuery("SELECT e FROM Commitment e WHERE e.obligor = ?1 AND e.tenancyId = ?2 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                   .setParameter(1, obligor).setParameter(2, tenancyId)
                   .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor) {
        if (obligor == null) return List.of();
        return em.createQuery("SELECT e FROM Commitment e WHERE e.obligor = ?1 AND e.state NOT IN ?2 AND e.tenancyId = ?3", CommitmentEntity.class)
                .setParameter(1, obligor).setParameter(2, terminalStates()).setParameter(3, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.requester = ?1 AND e.channelId = ?2 AND e.state NOT IN ?3 AND e.tenancyId = ?4", CommitmentEntity.class)
                .setParameter(1, requester).setParameter(2, channelId).setParameter(3, terminalStates()).setParameter(4, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findByState(CommitmentState state, UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.state = ?1 AND e.channelId = ?2 AND e.tenancyId = ?3", CommitmentEntity.class)
                .setParameter(1, state).setParameter(2, channelId).setParameter(3, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.channelId = ?1 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                   .setParameter(1, channelId)
                   .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByChannelId(UUID channelId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.channelId = ?1 AND e.state IN ?2 AND e.tenancyId = ?3", CommitmentEntity.class)
                         .setParameter(1, channelId)
                         .setParameter(2, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                         .setParameter(3, currentPrincipal.tenancyId())
                         .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }


    @Override
    public List<Commitment> findExpiredBefore(Instant cutoff) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.expiresAt < ?1 AND e.state NOT IN ?2 AND e.tenancyId = ?3", CommitmentEntity.class)
                .setParameter(1, cutoff).setParameter(2, terminalStates()).setParameter(3, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findAllOpen() {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.state IN ?1 AND e.tenancyId = ?2 ORDER BY e.expiresAt ASC NULLS LAST", CommitmentEntity.class)
                .setParameter(1, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                .setParameter(2, currentPrincipal.tenancyId())
                .getResultList()
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenOlderThan(Instant cutoff, String tenancyId) {
        return em.createQuery("SELECT e FROM Commitment e WHERE e.state IN ?1 AND e.createdAt < ?2 AND e.tenancyId = ?3 ORDER BY e.createdAt ASC", CommitmentEntity.class)
                           .setParameter(1, List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                           .setParameter(2, cutoff).setParameter(3, tenancyId != null ? tenancyId : currentPrincipal.tenancyId())
                           .getResultList()
                   .stream().map(CommitmentEntity::toDomain).toList();
    }


    @Override
    @Transactional
    public void deleteById(UUID id) {
        em.createQuery("DELETE FROM Commitment e WHERE e.id = ?1 AND e.tenancyId = ?2")
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    @Transactional
    public long deleteAll(UUID channelId) {
        return em.createQuery("DELETE FROM Commitment e WHERE e.channelId = ?1 AND e.tenancyId = ?2")
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    @Transactional
    public long deleteExpiredBefore(Instant cutoff) {
        return em.createQuery("DELETE FROM Commitment e WHERE e.expiresAt < ?1 AND e.state NOT IN ?2")
                .setParameter(1, cutoff).setParameter(2, terminalStates()).executeUpdate();
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}

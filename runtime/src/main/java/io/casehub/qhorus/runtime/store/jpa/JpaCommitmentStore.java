package io.casehub.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.casehub.qhorus.api.store.CommitmentStore;

@ApplicationScoped
public class JpaCommitmentStore implements CommitmentStore {

    @Inject
    CommitmentPanacheRepo repo;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public Commitment save(Commitment commitment) {
        CommitmentEntity c = CommitmentEntity.fromDomain(commitment);
        if (c.id == null) {
            repo.persist(c);
        } else {
            c = repo.getEntityManager().merge(c);
        }
        return c.toDomain();
    }

    @Override
    public Optional<Commitment> findById(UUID id) {
        return repo.find("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId())
                .<CommitmentEntity>firstResultOptional()
                .map(CommitmentEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Commitment> findByCorrelationId(String correlationId) {
        List<CommitmentEntity> all = repo.find("correlationId = ?1 AND tenancyId = ?2 ORDER BY createdAt DESC",
                        correlationId, currentPrincipal.tenancyId())
                .list();
        Optional<CommitmentEntity> active = all.stream()
                .filter(c -> c.state.isActive())
                .findFirst();
        return active.or(() -> all.stream().findFirst())
                .map(CommitmentEntity::toDomain);
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
        return repo.list(
                "obligor = ?1 AND channelId = ?2 AND state NOT IN ?3 AND tenancyId = ?4",
                obligor, channelId, terminalStates(), currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor) {
        if (obligor == null) return List.of();
        return repo.list(
                "obligor = ?1 AND state NOT IN ?2 AND tenancyId = ?3",
                obligor, terminalStates(), currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
        return repo.list(
                "requester = ?1 AND channelId = ?2 AND state NOT IN ?3 AND tenancyId = ?4",
                requester, channelId, terminalStates(), currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findByState(CommitmentState state, UUID channelId) {
        return repo.list("state = ?1 AND channelId = ?2 AND tenancyId = ?3",
                state, channelId, currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findExpiredBefore(Instant cutoff) {
        return repo.list(
                "expiresAt < ?1 AND state NOT IN ?2 AND tenancyId = ?3",
                cutoff, terminalStates(), currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findAllOpen() {
        return repo.list(
                "state IN ?1 AND tenancyId = ?2 ORDER BY expiresAt ASC NULLS LAST",
                List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED),
                currentPrincipal.tenancyId())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        repo.delete("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId());
    }

    @Override
    @Transactional
    public long deleteAll(UUID channelId) {
        return repo.delete("channelId = ?1 AND tenancyId = ?2", channelId, currentPrincipal.tenancyId());
    }

    @Override
    @Transactional
    public long deleteExpiredBefore(Instant cutoff) {
        return repo.delete("expiresAt < ?1 AND state NOT IN ?2", cutoff, terminalStates());
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}

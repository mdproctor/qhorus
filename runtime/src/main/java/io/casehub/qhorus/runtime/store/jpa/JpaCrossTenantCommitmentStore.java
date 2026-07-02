package io.casehub.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;

@ApplicationScoped
public class JpaCrossTenantCommitmentStore implements CrossTenantCommitmentStore {

    @Inject
    CommitmentPanacheRepo repo;

    @Override
    public List<Commitment> findAllOpen() {
        return repo.<CommitmentEntity>list(
                "state IN ?1 ORDER BY expiresAt ASC NULLS LAST",
                List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    public List<Commitment> findOpenByChannel(UUID channelId) {
        return repo.<CommitmentEntity>list(
                "channelId = ?1 AND state NOT IN ?2",
                channelId, terminalStates())
                .stream().map(CommitmentEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void expireOverdue(Instant cutoff) {
        List<CommitmentEntity> overdue = repo.list(
                "expiresAt < ?1 AND state NOT IN ?2",
                cutoff, terminalStates());
        Instant now = Instant.now();
        overdue.forEach(c -> {
            c.state = CommitmentState.EXPIRED;
            c.resolvedAt = now;
            repo.getEntityManager().merge(c);
        });
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}

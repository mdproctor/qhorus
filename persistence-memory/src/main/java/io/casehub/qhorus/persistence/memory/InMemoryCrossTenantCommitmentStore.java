package io.casehub.qhorus.persistence.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CrossTenantCommitmentStore;

/**
 * In-memory implementation of {@link CrossTenantCommitmentStore} for use in {@code @QuarkusTest} contexts.
 * Returns all commitments with no tenant filter — delegates to {@link InMemoryCommitmentStore}.
 *
 * <p>Refs #260.
 */
@Alternative
@Priority(1)
@ApplicationScoped
@CrossTenant
public class InMemoryCrossTenantCommitmentStore implements CrossTenantCommitmentStore {

    @Inject
    InMemoryCommitmentStore delegate;

    @Override
    public List<Commitment> findAllOpen() {
        return delegate.findAllOpen();
    }

    @Override
    public List<Commitment> findOpenByChannel(UUID channelId) {
        return delegate.findAllOpen().stream()
                .filter(c -> channelId.equals(c.channelId) && c.state.isActive())
                .toList();
    }

    @Override
    public void expireOverdue(Instant cutoff) {
        List<Commitment> overdue = delegate.findExpiredBefore(cutoff);
        Instant now = Instant.now();
        overdue.forEach(c -> {
            c.state = CommitmentState.EXPIRED;
            c.resolvedAt = now;
            delegate.save(c);
        });
    }
}

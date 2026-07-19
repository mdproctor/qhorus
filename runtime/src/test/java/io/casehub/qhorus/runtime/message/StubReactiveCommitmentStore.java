package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test stub satisfying the ReactiveCommitmentStore CDI dependency in @QuarkusTest runs
 * where no real JPA reactive implementation is available yet.
 *
 * <p>The real implementation (ReactiveJpaCommitmentStore) does not exist yet — tracked in
 * #193. Without this stub, ReactiveCommitmentService (gated by @IfBuildProperty
 * casehub.qhorus.reactive.enabled=true) fails CDI validation at build time when
 * the reactive-pg profile is active.
 *
 * <p>No method is expected to be called in tests that inject this stub — all throw.
 * ReactiveCommitmentServiceTest remains @Disabled until the real JPA implementation exists.
 */
@DefaultBean
@ApplicationScoped
class StubReactiveCommitmentStore implements ReactiveCommitmentStore {

    @Override
    public Uni<Commitment> save(final Commitment commitment) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<Optional<Commitment>> findById(final UUID commitmentId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<Optional<Commitment>> findByCorrelationId(final String correlationId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<List<Commitment>> findAllByCorrelationId(String correlationId) {
        throw new UnsupportedOperationException();
    }


    @Override
    public Uni<List<Commitment>> findByIds(java.util.Collection<UUID> ids) {
        throw new UnsupportedOperationException("stub");
    }


    @Override
    public Uni<List<Commitment>> findOpenByObligor(final String obligor, final UUID channelId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<List<Commitment>> findOpenByRequester(final String requester, final UUID channelId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<List<Commitment>> findByState(final CommitmentState state, final UUID channelId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<List<Commitment>> findByChannel(final UUID channelId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }


    @Override
    public Uni<List<Commitment>> findExpiredBefore(final Instant cutoff) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<List<Commitment>> findAllOpen() {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<Void> deleteById(final UUID commitmentId) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }

    @Override
    public Uni<Long> deleteExpiredBefore(final Instant cutoff) {
        throw new UnsupportedOperationException("reactive CommitmentStore not available — stub only");
    }
}

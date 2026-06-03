package io.casehub.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.ReactiveCommitmentStore;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

/**
 * Reactive JPA implementation of {@link ReactiveCommitmentStore}.
 *
 * <p>
 * Active when {@code casehub.qhorus.reactive.enabled=true}. All mutating methods
 * use {@code @WithTransaction("qhorus")} to target the named persistence unit.
 *
 * <p>
 * Refs #193.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveJpaCommitmentStore implements ReactiveCommitmentStore {

    @Inject
    CommitmentReactivePanacheRepo repo;

    @Override
    @WithTransaction("qhorus")
    public Uni<Commitment> save(Commitment c) {
        if (c.id == null) {
            return repo.persist(c);
        }
        return repo.getSession().flatMap(session -> session.merge(c));
    }

    @Override
    public Uni<Optional<Commitment>> findById(UUID commitmentId) {
        return repo.findById(commitmentId).map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<Commitment>> findByCorrelationId(String correlationId) {
        // Prefer the active (non-terminal) commitment — supports delegation chains where
        // multiple records share a correlationId.
        return repo.find("correlationId = ?1 ORDER BY createdAt DESC", correlationId)
                .list()
                .map(commitments -> commitments.stream()
                        .filter(c -> !c.state.isTerminal())
                        .findFirst()
                        .or(() -> commitments.stream().findFirst()));
    }

    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor, UUID channelId) {
        return repo.list(
                "obligor = ?1 AND channelId = ?2 AND state NOT IN ?3",
                obligor, channelId, terminalStates());
    }

    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor) {
        if (obligor == null) return Uni.createFrom().item(List.of());
        return repo.list("obligor = ?1 AND state NOT IN ?2",
                obligor, terminalStates());
    }

    @Override
    public Uni<List<Commitment>> findOpenByRequester(String requester, UUID channelId) {
        return repo.list(
                "requester = ?1 AND channelId = ?2 AND state NOT IN ?3",
                requester, channelId, terminalStates());
    }

    @Override
    public Uni<List<Commitment>> findByState(CommitmentState state, UUID channelId) {
        return repo.list("state = ?1 AND channelId = ?2", state, channelId);
    }

    @Override
    public Uni<List<Commitment>> findExpiredBefore(Instant cutoff) {
        return repo.list(
                "expiresAt < ?1 AND state NOT IN ?2",
                cutoff, terminalStates());
    }

    @Override
    public Uni<List<Commitment>> findAllOpen() {
        return repo.list(
                "state IN ?1 ORDER BY expiresAt ASC NULLS LAST",
                List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED));
    }

    @Override
    @WithTransaction("qhorus")
    public Uni<Void> deleteById(UUID commitmentId) {
        return repo.deleteById(commitmentId).replaceWithVoid();
    }

    @Override
    @WithTransaction("qhorus")
    public Uni<Long> deleteExpiredBefore(Instant cutoff) {
        return repo.delete("expiresAt < ?1 AND state NOT IN ?2", cutoff, terminalStates());
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}

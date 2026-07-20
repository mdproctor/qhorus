package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    public Uni<Commitment> save(Commitment commitment) {
        CommitmentEntity entity = CommitmentEntity.fromDomain(commitment);
        if (entity.id == null) {
            return repo.persist(entity).map(CommitmentEntity::toDomain);
        }
        return repo.getSession().flatMap(session -> session.merge(entity))
                .map(CommitmentEntity::toDomain);
    }

    @Override
    public Uni<Optional<Commitment>> findById(UUID commitmentId) {
        return repo.findById(commitmentId)
                .map(e -> Optional.ofNullable(e).map(CommitmentEntity::toDomain));
    }

    @Override
    public Uni<Optional<Commitment>> findByCorrelationId(String correlationId) {
        // Prefer the active (non-terminal) commitment — supports delegation chains where
        // multiple records share a correlationId.
        return repo.find("correlationId = ?1 ORDER BY createdAt DESC", correlationId)
                .<CommitmentEntity>list()
                .map(commitments -> {
                    List<Commitment> domains = commitments.stream()
                            .map(CommitmentEntity::toDomain).toList();
                    return domains.stream()
                            .filter(c -> c.state().isActive())
                            .findFirst()
                            .or(() -> domains.stream().findFirst());
                });
    }

    @Override
    public Uni<List<Commitment>> findAllByCorrelationId(String correlationId) {
        return repo.<CommitmentEntity>find("correlationId = ?1 ORDER BY createdAt ASC", correlationId)
                   .list()
                   .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }


    @Override
    public Uni<List<Commitment>> findByIds(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {return Uni.createFrom().item(List.of());}
        return repo.find("id IN ?1", List.copyOf(ids))
                   .list()
                   .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }


    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor, UUID channelId) {
        return repo.<CommitmentEntity>list(
                "obligor = ?1 AND channelId = ?2 AND state NOT IN ?3",
                obligor, channelId, terminalStates())
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor) {
        if (obligor == null) return Uni.createFrom().item(List.of());
        return repo.<CommitmentEntity>list("obligor = ?1 AND state NOT IN ?2",
                obligor, terminalStates())
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findOpenByRequester(String requester, UUID channelId) {
        return repo.<CommitmentEntity>list(
                "requester = ?1 AND channelId = ?2 AND state NOT IN ?3",
                requester, channelId, terminalStates())
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findByState(CommitmentState state, UUID channelId) {
        return repo.<CommitmentEntity>list("state = ?1 AND channelId = ?2", state, channelId)
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findByChannel(UUID channelId) {
        return repo.<CommitmentEntity>list("channelId = ?1 ORDER BY createdAt ASC", channelId)
                   .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findOpenByChannelIdAsync(UUID channelId) {
        return repo.list("channelId = ?1 AND state IN ?2",
                         channelId,
                         List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                   .map(entities -> entities.stream()
                                            .map(io.casehub.qhorus.runtime.message.CommitmentEntity::toDomain)
                                            .toList());
    }


    @Override
    public Uni<List<Commitment>> findExpiredBefore(Instant cutoff) {
        return repo.<CommitmentEntity>list(
                "expiresAt < ?1 AND state NOT IN ?2",
                cutoff, terminalStates())
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
    }

    @Override
    public Uni<List<Commitment>> findAllOpen() {
        return repo.<CommitmentEntity>list(
                "state IN ?1 ORDER BY expiresAt ASC NULLS LAST",
                List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED))
                .map(list -> list.stream().map(CommitmentEntity::toDomain).toList());
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

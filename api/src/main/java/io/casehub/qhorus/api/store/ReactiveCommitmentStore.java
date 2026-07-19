package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.smallrye.mutiny.Uni;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReactiveCommitmentStore {

    Uni<Commitment> save(Commitment commitment);

    Uni<Optional<Commitment>> findById(UUID commitmentId);

    Uni<Optional<Commitment>> findByCorrelationId(String correlationId);

    /**
     * All commitments sharing a correlationId, ordered by createdAt ASC.
     */
    Uni<List<Commitment>> findAllByCorrelationId(String correlationId);


    Uni<List<Commitment>> findByIds(Collection<UUID> ids);

    Uni<List<Commitment>> findOpenByObligor(String obligor, UUID channelId);

    Uni<List<Commitment>> findOpenByRequester(String requester, UUID channelId);

    Uni<List<Commitment>> findByState(CommitmentState state, UUID channelId);

    Uni<List<Commitment>> findByChannel(UUID channelId);


    Uni<List<Commitment>> findExpiredBefore(Instant cutoff);

    Uni<List<Commitment>> findAllOpen();

    /**
     * Reactive equivalent of {@link io.casehub.qhorus.api.store.CommitmentStore#findOpenByObligor(String)}.
     *
     * <p>WARNING: This default is a full scan — JPA-backed implementations MUST override.
     *
     * @param obligor the actor identity string; returns empty list if null
     */
    default io.smallrye.mutiny.Uni<List<Commitment>> findOpenByObligor(String obligor) {
        if (obligor == null) {return io.smallrye.mutiny.Uni.createFrom().item(List.of());}
        return findAllOpen().map(all -> all.stream()
                                           .filter(c -> obligor.equals(c.obligor()))
                                           .toList());
    }

    Uni<Void> deleteById(UUID commitmentId);

    Uni<Long> deleteExpiredBefore(Instant cutoff);
}

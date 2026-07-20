package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommitmentStore {

    Commitment save(Commitment commitment);

    Optional<Commitment> findById(UUID commitmentId);

    Optional<Commitment> findByCorrelationId(String correlationId);

    /**
     * All commitments sharing a correlationId, ordered by createdAt ASC.
     */
    List<Commitment> findAllByCorrelationId(String correlationId);


    List<Commitment> findByIds(Collection<UUID> ids);

    /**
     * All non-terminal commitments where this agent is the obligor (what do I owe?).
     */
    List<Commitment> findOpenByObligor(String obligor, UUID channelId);

    /**
     * All non-terminal commitments where this agent is the requester (what's owed to me?).
     */
    List<Commitment> findOpenByRequester(String requester, UUID channelId);

    /**
     * All commitments in a given state on a channel.
     */
    List<Commitment> findByState(CommitmentState state, UUID channelId);

    /**
     * All commitments for a channel, regardless of state.
     */
    List<Commitment> findByChannel(UUID channelId);

    List<Commitment> findOpenByChannelId(UUID channelId);


    /** All OPEN or ACKNOWLEDGED commitments whose expiresAt is strictly before the cutoff. */
    List<Commitment> findExpiredBefore(Instant cutoff);

    /**
     * All OPEN or ACKNOWLEDGED commitments across all channels, sorted oldest first.
     */
    List<Commitment> findAllOpen();

    /**
     * All non-terminal commitments where this actor is the obligor, across all channels.
     *
     * <p>WARNING: This default is a full table scan over all open commitments —
     * JPA-backed implementations MUST override with an indexed query.
     * This default is correct only for in-memory test implementations.
     *
     * @param obligor the actor identity string; returns empty list if null
     */
    default List<Commitment> findOpenByObligor(String obligor) {
        if (obligor == null) {return java.util.List.of();}
        return findAllOpen().stream()
                            .filter(c -> obligor.equals(c.obligor()))
                            .toList();
    }

    void deleteById(UUID commitmentId);

    /**
     * Delete all commitments for the given channel. Called by delete_channel before channel deletion.
     */
    long deleteAll(UUID channelId);

    long deleteExpiredBefore(Instant cutoff);
}

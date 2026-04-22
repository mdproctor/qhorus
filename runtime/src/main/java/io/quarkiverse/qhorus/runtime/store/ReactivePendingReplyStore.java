package io.quarkiverse.qhorus.runtime.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.smallrye.mutiny.Uni;

public interface ReactivePendingReplyStore {

    /** Persist a new PendingReply or update an existing one (matched by id). */
    Uni<PendingReply> save(PendingReply pr);

    Uni<Optional<PendingReply>> findByCorrelationId(String correlationId);

    Uni<Void> deleteByCorrelationId(String correlationId);

    Uni<Boolean> existsByCorrelationId(String correlationId);

    /** All entries whose expiresAt is strictly before the given cutoff. */
    Uni<List<PendingReply>> findExpiredBefore(Instant cutoff);

    /** Delete all entries whose expiresAt is strictly before the given cutoff. Returns the number of deleted entries. */
    Uni<Long> deleteExpiredBefore(Instant cutoff);
}

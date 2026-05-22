package io.casehub.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.annotation.Nullable;

/**
 * Return carrier from {@link LedgerWriteService#record}. Carries the three resolved field values
 * (entryId, subjectId, causedByEntryId) back to {@link io.casehub.qhorus.runtime.message.MessageService}
 * so they can be included in {@code DispatchResult} without a secondary ledger query.
 *
 * <p>Contract: {@code entryId} is null only when ledger writes are suppressed via config (see
 * {@link #DISABLED}). Write failures are never caught — {@code record()} propagates exceptions,
 * causing the caller's {@code @Transactional} boundary to roll back. There is no "write failed"
 * sentinel because no exception is swallowed.
 */
public record LedgerWriteOutcome(
        @Nullable UUID entryId,
        @Nullable UUID subjectId,
        @Nullable UUID causedByEntryId) {

    /** Sentinel returned when ledger writes are suppressed via {@code casehub.ledger.enabled=false}. */
    public static final LedgerWriteOutcome DISABLED = new LedgerWriteOutcome(null, null, null);
}

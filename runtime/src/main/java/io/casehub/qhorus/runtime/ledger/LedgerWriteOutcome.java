package io.casehub.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.annotation.Nullable;

/**
 * Return carrier from {@link LedgerWriteService#record}. Carries resolved field values back to
 * {@link io.casehub.qhorus.runtime.message.MessageService}.
 */
public record LedgerWriteOutcome(
        @Nullable UUID entryId,
        @Nullable UUID subjectId,
        @Nullable UUID causedByEntryId) {

    /** Sentinel returned when ledger writes are suppressed via config. */
    public static final LedgerWriteOutcome DISABLED = new LedgerWriteOutcome(null, null, null);
}

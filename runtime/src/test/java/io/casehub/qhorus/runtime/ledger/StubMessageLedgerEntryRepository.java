package io.casehub.qhorus.runtime.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.platform.api.identity.TenancyConstants;

/**
 * In-memory stub of {@link MessageLedgerEntryRepository} for CDI-free unit tests.
 *
 * <p>Accepts a shared {@code List<LedgerEntry>} so that entries saved via
 * {@link StubLedgerEntryRepository#save} are visible here for
 * {@link #findByMessageId} and {@link #findEarliestWithSubjectByCorrelationId}.
 *
 * <p>All overriding methods apply the same tenancyId normalisation as the production repository:
 * null is treated as {@link TenancyConstants#DEFAULT_TENANT_ID}. Entries in the list with null
 * tenancyId are also normalised to DEFAULT_TENANT_ID for comparison purposes.
 *
 * <p>Refs qhorus#253, #263.
 */
public class StubMessageLedgerEntryRepository extends MessageLedgerEntryRepository {

    // ⚠️ The inherited `em` (EntityManager) field is null in CDI-free unit tests —
    // any non-overridden method (findByChannelId, findStalledCommands, etc.) will NPE.
    // Override with UnsupportedOperationException if you need to call other methods in tests.
    final List<LedgerEntry> entries;

    public StubMessageLedgerEntryRepository(final List<LedgerEntry> entries) {
        this.entries = entries;
    }

    /** No-arg constructor — creates its own list (used when not sharing with a ledger stub). */
    public StubMessageLedgerEntryRepository() {
        this.entries = new ArrayList<>();
    }

    @Override
    public Optional<MessageLedgerEntry> findByMessageId(final Long messageId) { // unchanged — PK-based
        return entries.stream()
                .filter(e -> e instanceof MessageLedgerEntry m && messageId.equals(m.messageId))
                .map(e -> (MessageLedgerEntry) e)
                .findFirst();
    }

    @Override
    public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(
            final String correlationId, final String tenancyId) {
        final String tid = effective(tenancyId);
        return entries.stream()
                .filter(e -> e instanceof MessageLedgerEntry m
                        && correlationId.equals(m.correlationId)
                        && m.subjectId != null
                        && tid.equals(effective(m.tenancyId)))
                .map(e -> (MessageLedgerEntry) e)
                .min(Comparator.comparingInt(e -> e.sequenceNumber));
    }

    @Override
    public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
            final String correlationId, final String tenancyId) {
        final String tid = effective(tenancyId);
        return entries.stream()
                .filter(e -> e instanceof MessageLedgerEntry m
                        && channelId.equals(m.subjectId)
                        && correlationId.equals(m.correlationId)
                        && ("COMMAND".equals(m.messageType) || "HANDOFF".equals(m.messageType))
                        && tid.equals(effective(m.tenancyId)))
                .map(e -> (MessageLedgerEntry) e)
                .reduce((a, b) -> b);
    }

    private static String effective(final String t) {
        return t != null ? t : TenancyConstants.DEFAULT_TENANT_ID;
    }
}

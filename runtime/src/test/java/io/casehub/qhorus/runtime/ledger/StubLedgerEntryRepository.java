package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;

/**
 * In-memory stub of {@link LedgerEntryRepository} for CDI-free unit tests.
 *
 * <p>Implements {@link LedgerEntryRepository} directly — do NOT extend
 * {@code JpaLedgerEntryRepository} (which injects JPA infrastructure).
 *
 * <p>The {@code entries} list is injected via constructor so it can be shared with
 * {@link StubMessageLedgerEntryRepository}, allowing {@code save()} writes to be
 * visible to qhorus-specific lookups like {@code findByMessageId()}.
 *
 * <p>Refs qhorus#253, qhorus#255.
 */
public class StubLedgerEntryRepository implements LedgerEntryRepository {

    final List<LedgerEntry> entries;
    public final List<LedgerAttestation> savedAttestations = new ArrayList<>();
    // Mirrors LedgerSequenceAllocator behaviour: per-subjectId counter, starts at 1. Refs #256.
    private final Map<UUID, Integer> sequenceCounters = new HashMap<>();

    public StubLedgerEntryRepository(final List<LedgerEntry> entries) {
        this.entries = entries;
    }

    @Override
    public LedgerEntry save(final LedgerEntry entry, final String tenancyId) {
        if (entry.id == null) {
            entry.id = UUID.randomUUID(); // simulate @PrePersist id assignment
        }
        // Simulate LedgerSequenceAllocator.nextSequenceNumber() — overrides any caller-set value.
        entry.sequenceNumber = sequenceCounters.merge(entry.subjectId, 1, Integer::sum);
        entries.add(entry);
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId, final String tenancyId) {
        return entries.stream()
                .filter(e -> subjectId.equals(e.subjectId))
                .max(Comparator.comparingInt(e -> e.sequenceNumber));
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id, final String tenancyId) {
        return entries.stream()
                .filter(e -> id.equals(e.id))
                .findFirst();
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation, final String tenancyId) {
        savedAttestations.add(attestation);
        return attestation;
    }

    // ── Remaining interface methods — no-op stubs (not exercised by LedgerWriteService unit tests) ──

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(final UUID s, final Instant f, final Instant t, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByActorId(final String a, final Instant f, final Instant t, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String r, final Instant f, final Instant t, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findCausedBy(final UUID id, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID id, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(
            final UUID id, final String tag, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(final UUID id, final String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(
            final String a, final String t, final String tenancyId) {
        return List.of();
    }
}

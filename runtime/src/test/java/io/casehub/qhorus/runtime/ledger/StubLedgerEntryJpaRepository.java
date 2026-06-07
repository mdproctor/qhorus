package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;

/**
 * In-memory stub of {@link LedgerEntryJpaRepository} for CDI-free unit tests.
 *
 * <p>Implements {@link LedgerEntryRepository} directly — do NOT extend
 * {@link LedgerEntryJpaRepository} (which injects {@code @PersistenceUnit EntityManager}).
 *
 * <p>The {@code entries} list is injected via constructor so it can be shared with
 * {@link StubMessageLedgerEntryRepository}, allowing {@code save()} writes to be
 * visible to qhorus-specific lookups like {@code findByMessageId()}.
 *
 * <p>Refs qhorus#253.
 */
public class StubLedgerEntryJpaRepository implements LedgerEntryRepository {

    final List<LedgerEntry> entries;
    public final List<LedgerAttestation> savedAttestations = new ArrayList<>();

    public StubLedgerEntryJpaRepository(final List<LedgerEntry> entries) {
        this.entries = entries;
    }

    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        if (entry.id == null) {
            entry.id = UUID.randomUUID(); // simulate @PrePersist id assignment
        }
        entries.add(entry);
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return entries.stream()
                .filter(e -> subjectId.equals(e.subjectId))
                .max(Comparator.comparingInt(e -> e.sequenceNumber));
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        return entries.stream()
                .filter(e -> id.equals(e.id))
                .findFirst();
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        savedAttestations.add(attestation);
        return attestation;
    }

    // ── Remaining interface methods — no-op stubs (not exercised by LedgerWriteService unit tests) ──

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(final UUID s, final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> listAll() {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findEventsByActorId(final String a) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByActorId(final String a, final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String r, final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByTimeRange(final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findCausedBy(final UUID id) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID id) {
        return List.of();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> ids) {
        return Map.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(
            final UUID id, final String tag) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(final UUID id) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(
            final String a, final String t) {
        return List.of();
    }
}

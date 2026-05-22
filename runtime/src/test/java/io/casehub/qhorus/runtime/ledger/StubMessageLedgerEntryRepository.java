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

/** In-memory stub of {@link MessageLedgerEntryRepository} for CDI-free unit tests. */
class StubMessageLedgerEntryRepository extends MessageLedgerEntryRepository {

    final List<MessageLedgerEntry> entries = new ArrayList<>();

    StubMessageLedgerEntryRepository() {
        // no CDI, no EntityManager needed — override every used method
    }

    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        final MessageLedgerEntry mle = (MessageLedgerEntry) entry;
        // Simulate @PrePersist — assign id if absent
        if (mle.id == null) {
            mle.id = UUID.randomUUID();
        }
        entries.add(mle);
        return mle;
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return entries.stream()
                .filter(e -> subjectId.equals(e.subjectId))
                .max(Comparator.comparingInt(e -> e.sequenceNumber))
                .map(e -> (LedgerEntry) e);
    }

    @Override
    public Optional<MessageLedgerEntry> findByMessageId(final Long messageId) {
        return entries.stream()
                .filter(e -> messageId.equals(e.messageId))
                .findFirst();
    }

    @Override
    public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(
            final String correlationId) {
        return entries.stream()
                .filter(e -> correlationId.equals(e.correlationId) && e.subjectId != null)
                .min(Comparator.comparingInt(e -> e.sequenceNumber));
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        return entries.stream()
                .filter(e -> id.equals(e.id))
                .map(e -> (LedgerEntry) e)
                .findFirst();
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation a) {
        return a;
    }

    // remaining abstract methods — no-op stubs

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID s) {
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

    @Override
    public List<LedgerEntry> findByActorId(final String a, final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String r, final Instant f, final Instant t) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(
            final UUID s, final Instant f, final Instant t) {
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
}

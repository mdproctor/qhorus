package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.ReactiveLedgerEntryRepository;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;

/**
 * Test stub satisfying the ReactiveLedgerEntryRepository CDI dependency in H2/JDBC
 * non-reactive @QuarkusTest runs (casehub.qhorus.reactive.enabled absent or false).
 *
 * The real implementation (ReactiveLedgerEntryJpaRepository) is gated by
 * @IfBuildProperty(casehub.qhorus.reactive.enabled=true) and is absent in H2 test contexts.
 * Without this stub, casehub-ledger beans that inject ReactiveLedgerEntryRepository
 * (LedgerVerificationService, KeyRotationService) fail CDI validation at build time.
 *
 * No method is expected to be called in non-reactive tests — all throw.
 */
@DefaultBean
@ApplicationScoped
class StubReactiveLedgerEntryRepository implements ReactiveLedgerEntryRepository {

    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerEntry>> findBySubjectIdAndTimeRange(
            final UUID subjectId, final Instant from, final Instant to, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerEntry>> findByActorId(final String actorId, final Instant from, final Instant to, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole, final Instant from, final Instant to, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(
            final UUID entryId, final String capabilityTag, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdGlobal(final UUID entryId, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(
            final String attestorId, final String capabilityTag, final String tenancyId) {
        throw new UnsupportedOperationException("reactive ledger not available in non-reactive tests");
    }
}

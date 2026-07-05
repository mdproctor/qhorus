package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.runtime.model.LedgerMerkleFrontier;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.api.spi.ActorIdentityProvider;
import io.casehub.ledger.runtime.privacy.DecisionContextSanitiser;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.runtime.repository.LedgerMerkleFrontierRepository;
import io.casehub.ledger.runtime.service.AttestationRecordedEvent;
import io.casehub.ledger.runtime.service.LedgerMerklePublisher;
import io.casehub.ledger.runtime.service.LedgerMerkleTree;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * Qhorus's default {@link LedgerEntryRepository} bean.
 *
 * <p>Implements {@link LedgerEntryRepository} directly (does not extend
 * {@link io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository}) so that
 * qhorus can control sequence allocation using {@link QhorusSequenceAllocator} (a
 * {@code REQUIRES_NEW} bean that commits the MERGE immediately). This eliminates the
 * H2 concurrent-insert PK violation that occurs when multiple REQUIRES_NEW transactions
 * concurrently enter the MERGE WHEN-NOT-MATCHED branch before any commits.
 *
 * <p>Matches the full behaviour of {@code JpaLedgerEntryRepository}: actorId tokenisation,
 * decision-context sanitisation, Merkle hash chain, and Merkle frontier update.
 *
 * <p>Adds null-safety: any {@code tenancyId} parameter arriving as {@code null} is
 * defaulted to {@link TenancyConstants#DEFAULT_TENANT_ID} before persisting or querying.
 *
 * <p>Refs qhorus#255, qhorus#256.
 */
@ApplicationScoped
class QhorusLedgerEntryRepository implements LedgerEntryRepository {

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Inject
    QhorusSequenceAllocator sequenceAllocator;

    @Inject
    LedgerConfig ledgerConfig;

    @Inject
    LedgerMerklePublisher merklePublisher;

    @Inject
    LedgerMerkleFrontierRepository frontierRepo;

    @Inject
    ActorIdentityProvider actorIdentityProvider;

    @Inject
    DecisionContextSanitiser decisionContextSanitiser;

    @Inject
    Event<AttestationRecordedEvent> attestationRecordedEvent;

    /**
     * Atomically allocates a sequence number (committed in its own REQUIRES_NEW),
     * then persists the entry with Merkle evidence. The {@code synchronized} ensures
     * concurrent callers see T1's committed sequence row before starting their own MERGE.
     *
     * <p>{@code @Transactional(REQUIRED)} makes the transaction contract explicit: if the
     * caller has a transaction (e.g. {@code LedgerWriteService.record()} with REQUIRES_NEW)
     * this method joins it; without a caller transaction, a new one starts. Without this
     * annotation, missing a transaction context produces an opaque JPA error at persist time.
     */
    @Transactional(TxType.REQUIRED)
    @Override
    public synchronized LedgerEntry save(final LedgerEntry entry, final String tenancyId) {
        entry.tenancyId = tenancyId(tenancyId);
        if (entry.subjectId == null) {
            throw new IllegalArgumentException("LedgerEntry.subjectId must not be null");
        }
        if (entry.occurredAt == null) {
            entry.occurredAt = Instant.now();
        }
        if (entry.actorId != null) {
            entry.actorId = actorIdentityProvider.tokenise(entry.actorId, entry.actorType);
        }
        entry.compliance().ifPresent(cs -> {
            if (cs.decisionContext != null) {
                cs.decisionContext = decisionContextSanitiser.sanitise(cs.decisionContext);
                entry.refreshSupplementJson();
            }
        });
        // Allocate sequence in REQUIRES_NEW — commits immediately, visible to concurrent
        // transactions before this synchronized block exits.
        entry.sequenceNumber = sequenceAllocator.nextSequenceNumber(entry.subjectId);
        if (ledgerConfig.hashChain().enabled()) {
            entry.digest = LedgerMerkleTree.leafHash(entry);
        }
        em.persist(entry);
        if (ledgerConfig.hashChain().enabled()) {
            updateMerkleFrontier(entry, entry.tenancyId);
        }
        return entry;
    }

    private void updateMerkleFrontier(final LedgerEntry entry, final String tenancyId) {
        final List<LedgerMerkleFrontier> currentFrontier =
                frontierRepo.findBySubjectId(entry.subjectId, tenancyId);
        final List<LedgerMerkleFrontier> newFrontier =
                LedgerMerkleTree.append(entry.digest, currentFrontier, entry.subjectId);
        frontierRepo.replace(entry.subjectId, newFrontier, tenancyId);
        merklePublisher.publish(entry.subjectId, entry.sequenceNumber,
                LedgerMerkleTree.treeRoot(newFrontier));
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation, final String tenancyId) {
        final LedgerEntry entry = em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.id = :id AND e.tenancyId = :tid",
                LedgerEntry.class)
                .setParameter("id", attestation.ledgerEntryId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "LedgerEntry " + attestation.ledgerEntryId + " not found in tenant " + tenancyId));
        if (attestation.attestorId != null) {
            attestation.attestorId = actorIdentityProvider.tokenise(attestation.attestorId, null);
        }
        em.persist(attestation);
        if (entry.actorId != null) {
            attestationRecordedEvent.fire(
                    new AttestationRecordedEvent(entry.actorId, entry.id, attestation.id));
        }
        return attestation;
    }

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid AND e.tenancyId = :tid ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(final UUID subjectId,
            final Instant from, final Instant to, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid AND e.tenancyId = :tid" +
                " AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setParameter("tid", tenancyId(tenancyId))
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid AND e.tenancyId = :tid ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setParameter("tid", tenancyId(tenancyId))
                .setMaxResults(1)
                .getResultStream().findFirst();
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.id = :id AND e.tenancyId = :tid",
                LedgerEntry.class)
                .setParameter("id", id)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultStream().findFirst();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId, final String tenancyId) {
        return em.createQuery(
                "SELECT a FROM LedgerAttestation a JOIN LedgerEntry e ON a.ledgerEntryId = e.id" +
                " WHERE a.ledgerEntryId = :eid AND e.tenancyId = :tid ORDER BY a.occurredAt ASC",
                LedgerAttestation.class)
                .setParameter("eid", ledgerEntryId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByActorId(final String actorId,
            final Instant from, final Instant to, final String tenancyId) {
        final java.util.Optional<String> tokenOpt = actorIdentityProvider.tokeniseForQuery(actorId);
        if (tokenOpt.isEmpty()) return List.of();
        final String token = tokenOpt.get();
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorId = :aid AND e.tenancyId = :tid" +
                " AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("aid", token)
                .setParameter("tid", tenancyId(tenancyId))
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole,
            final Instant from, final Instant to, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :role AND e.tenancyId = :tid" +
                " AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("role", actorRole)
                .setParameter("tid", tenancyId(tenancyId))
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :eid AND e.tenancyId = :tid" +
                " ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("eid", entryId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(
            final UUID entryId, final String capabilityTag, final String tenancyId) {
        return em.createQuery(
                "SELECT a FROM LedgerAttestation a JOIN LedgerEntry e ON a.ledgerEntryId = e.id" +
                " WHERE a.ledgerEntryId = :eid AND a.capabilityTag = :cap AND e.tenancyId = :tid" +
                " ORDER BY a.occurredAt ASC",
                LedgerAttestation.class)
                .setParameter("eid", entryId)
                .setParameter("cap", capabilityTag)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(final UUID entryId, final String tenancyId) {
        return em.createQuery(
                "SELECT a FROM LedgerAttestation a JOIN LedgerEntry e ON a.ledgerEntryId = e.id" +
                " WHERE a.ledgerEntryId = :eid AND a.capabilityTag = '*' AND e.tenancyId = :tid" +
                " ORDER BY a.occurredAt ASC",
                LedgerAttestation.class)
                .setParameter("eid", entryId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(
            final String attestorId, final String capabilityTag, final String tenancyId) {
        final java.util.Optional<String> tokenOpt = actorIdentityProvider.tokeniseForQuery(attestorId);
        if (tokenOpt.isEmpty()) return List.of();
        final String token = tokenOpt.get();
        return em.createQuery(
                "SELECT a FROM LedgerAttestation a JOIN LedgerEntry e ON a.ledgerEntryId = e.id" +
                " WHERE a.attestorId = :aid AND a.capabilityTag = :cap AND e.tenancyId = :tid" +
                " ORDER BY a.occurredAt ASC",
                LedgerAttestation.class)
                .setParameter("aid", token)
                .setParameter("cap", capabilityTag)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    private static String tenancyId(final String tenancyId) {
        return tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;
    }
}

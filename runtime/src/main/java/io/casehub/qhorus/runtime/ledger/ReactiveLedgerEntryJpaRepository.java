package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.runtime.model.LedgerMerkleFrontier;
import io.casehub.ledger.api.spi.ActorIdentityProvider;
import io.casehub.ledger.api.spi.ReactiveLedgerEntryRepository;
import io.casehub.ledger.runtime.service.LedgerMerklePublisher;
import io.casehub.ledger.runtime.service.LedgerMerkleTree;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive implementation of {@link ReactiveLedgerEntryRepository} for qhorus.
 *
 * <p>All queries target the {@code LedgerEntry} base class via raw Hibernate Reactive
 * JPQL — never through {@link MessageReactivePanacheRepo} (which scopes to
 * {@code MessageLedgerEntry} only). Session access uses {@code repo.getSession()}.
 *
 * <p>Gated by {@code @IfBuildProperty} because this class injects
 * {@link MessageReactivePanacheRepo}, which is itself gated. Removing the gate
 * would cause a CDI build-time unsatisfied injection failure in non-reactive builds.
 *
 * <p>In non-reactive builds, {@link StubReactiveLedgerEntryRepository} ({@code @DefaultBean})
 * satisfies the {@link ReactiveLedgerEntryRepository} injection point instead.
 *
 * <p><strong>Sequence number:</strong> {@code save()} assigns {@code entry.sequenceNumber}
 * via the same MERGE pattern as {@code LedgerSequenceAllocator} — atomic and race-free.
 * Refs qhorus#256.
 *
 * <p><strong>Merkle hash chain:</strong> {@code save()} computes {@code entry.digest} and
 * updates the Merkle frontier when {@code casehub.ledger.hash-chain.enabled=true},
 * matching the behaviour of {@code JpaLedgerEntryRepository.save()} so both stacks produce
 * consistent tamper-evident records. Refs qhorus#256.
 *
 * <p><strong>Tenancy:</strong> {@code tenancyId} parameters are accepted but not yet applied
 * to query filters — full tenant isolation wiring is tracked in qhorus#260 Task 14.
 *
 * <p>Refs qhorus#253, qhorus#256, qhorus#260.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveLedgerEntryJpaRepository implements ReactiveLedgerEntryRepository {

    @Inject
    MessageReactivePanacheRepo repo; // session access only — never used for typed Panache queries

    @Inject
    ActorIdentityProvider actorIdentityProvider;

    @Inject
    LedgerConfig ledgerConfig;

    @Inject
    LedgerMerklePublisher merklePublisher;

    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry, final String tenancyId) {
        entry.tenancyId = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;
        return repo.getSession().flatMap(session ->
            // Step 1: atomic sequence allocation — MERGE + flush + SELECT. Refs #256.
            session.createNativeQuery("""
                    MERGE INTO ledger_subject_sequence AS t \
                    USING (SELECT CAST(?1 AS UUID) AS sid) AS s ON t.subject_id = s.sid \
                    WHEN MATCHED THEN UPDATE SET next_seq = t.next_seq + 1 \
                    WHEN NOT MATCHED THEN INSERT (subject_id, next_seq) VALUES (s.sid, 2)\
                    """)
                .setParameter(1, entry.subjectId).executeUpdate()
            .flatMap(i -> session.flush())
            .flatMap(v -> session.createNativeQuery(
                    "SELECT next_seq - 1 FROM ledger_subject_sequence WHERE subject_id = ?1",
                    Integer.class)
                .setParameter(1, entry.subjectId).getSingleResultOrNull())
            .flatMap(seq -> {
                entry.sequenceNumber = seq != null ? seq : 1;
                // Step 2: tokenise actorId before leafHash so both stacks produce identical digests. Refs #256.
                if (entry.actorId != null) {
                    entry.actorId = actorIdentityProvider.tokenise(entry.actorId, entry.actorType);
                }
                // Step 3: compute Merkle digest (pure Java — must be after sequence + tokenisation).
                if (ledgerConfig.hashChain().enabled()) {
                    entry.digest = LedgerMerkleTree.leafHash(entry);
                }
                // Step 4: persist entry.
                return session.persist(entry).replaceWith(entry);
            })
            .flatMap(e -> {
                // Step 5: Merkle frontier update (conditional). Refs #256.
                if (!ledgerConfig.hashChain().enabled()) {
                    return Uni.createFrom().item(e);
                }
                return session.createQuery(
                        "SELECT f FROM LedgerMerkleFrontier f WHERE f.subjectId = :sid ORDER BY f.level ASC",
                        LedgerMerkleFrontier.class)
                    .setParameter("sid", e.subjectId)
                    .getResultList()
                    .flatMap(currentFrontier -> {
                        final List<LedgerMerkleFrontier> newFrontier =
                                LedgerMerkleTree.append(e.digest, currentFrontier, e.subjectId);
                        newFrontier.forEach(node -> node.tenancyId = e.tenancyId);
                        final Set<Integer> newLevels = newFrontier.stream()
                                .map(n -> n.level).collect(Collectors.toSet());
                        // Delete frontier levels not in the new set (mirrors JpaLedgerMerkleFrontierRepository.replace()).
                        final Uni<Integer> deleteOld = newLevels.isEmpty()
                                ? Uni.createFrom().item(0)
                                : session.createMutationQuery(
                                    "DELETE FROM LedgerMerkleFrontier f WHERE f.subjectId = :sid AND f.level NOT IN :levels")
                                  .setParameter("sid", e.subjectId)
                                  .setParameter("levels", newLevels)
                                  .executeUpdate();
                        return deleteOld.flatMap(del -> {
                            // Per-node: delete exact level then persist new node.
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (final LedgerMerkleFrontier node : newFrontier) {
                                chain = chain
                                    .flatMap(v -> session
                                        .createNamedQuery("LedgerMerkleFrontier.deleteBySubjectAndLevel")
                                        .setParameter("subjectId", e.subjectId)
                                        .setParameter("level", node.level)
                                        .setParameter("tenancyId", e.tenancyId)
                                        .executeUpdate()
                                        .replaceWithVoid())
                                    .flatMap(v -> session.persist(node));
                            }
                            return chain;
                        }).invoke(() -> merklePublisher.publish(
                                e.subjectId, e.sequenceNumber, LedgerMerkleTree.treeRoot(newFrontier)))
                        .replaceWith(e);
                    });
            }));
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid " +
                                        "ORDER BY e.sequenceNumber DESC",
                                LedgerEntry.class)
                        .setParameter("sid", subjectId)
                        .setMaxResults(1)
                        .getSingleResultOrNull()
                        .map(Optional::ofNullable));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid " +
                                        "ORDER BY e.sequenceNumber ASC",
                                LedgerEntry.class)
                        .setParameter("sid", subjectId)
                        .getResultList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectIdAndTimeRange(final UUID subjectId,
            final Instant from, final Instant to, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid " +
                                        "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                                        "ORDER BY e.occurredAt ASC",
                                LedgerEntry.class)
                        .setParameter("sid", subjectId)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList());
    }

    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        // find() on the abstract base class is correct — Hibernate Reactive resolves the concrete subtype
        return repo.getSession()
                .flatMap(session -> session.find(LedgerEntry.class, id).map(Optional::ofNullable));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorId(final String actorId,
            final Instant from, final Instant to, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.actorId = :aid " +
                                        "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                                        "ORDER BY e.occurredAt ASC",
                                LedgerEntry.class)
                        .setParameter("aid", actorId)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole,
            final Instant from, final Instant to, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :role " +
                                        "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                                        "ORDER BY e.occurredAt ASC",
                                LedgerEntry.class)
                        .setParameter("role", actorRole)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :eid " +
                                        "ORDER BY e.sequenceNumber ASC",
                                LedgerEntry.class)
                        .setParameter("eid", entryId)
                        .getResultList());
    }

    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation, final String tenancyId) {
        // TODO: apply tenancyId to attestation before persist (qhorus#260 Task 14)
        if (attestation.attestorId != null) {
            attestation.attestorId = actorIdentityProvider.tokenise(attestation.attestorId, null);
        }
        return repo.getSession()
                .flatMap(session -> session.persist(attestation).replaceWith(attestation));
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
                        .setParameter("entryId", ledgerEntryId)
                        .getResultList());
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(
            final UUID entryId, final String capabilityTag, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByEntryIdAndCapabilityTag",
                                LedgerAttestation.class)
                        .setParameter("entryId", entryId)
                        .setParameter("capabilityTag", capabilityTag)
                        .getResultList());
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdGlobal(final UUID ledgerEntryId, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findGlobalByEntryId", LedgerAttestation.class)
                        .setParameter("entryId", ledgerEntryId)
                        .getResultList());
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(
            final String attestorId, final String capabilityTag, final String tenancyId) {
        // TODO: apply tenancyId filter (qhorus#260 Task 14)
        final java.util.Optional<String> tokenOpt = actorIdentityProvider.tokeniseForQuery(attestorId);
        if (tokenOpt.isEmpty()) return Uni.createFrom().item(List.of());
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByAttestorIdAndCapabilityTag",
                                LedgerAttestation.class)
                        .setParameter("attestorId", tokenOpt.get())
                        .setParameter("capabilityTag", capabilityTag)
                        .getResultList());
    }
}

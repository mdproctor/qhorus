package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * Qhorus's blocking implementation of {@link LedgerEntryRepository}.
 *
 * <p>All queries target the {@code LedgerEntry} base class, so results span every
 * registered {@code LedgerEntry} subtype (dtype-agnostic). This is the correct
 * behaviour for any cross-dtype concern such as sequence-number assignment.
 *
 * <p>This class is {@code @ApplicationScoped} without any {@code @Priority}, so it
 * becomes the sole active {@code LedgerEntryRepository} bean once
 * {@link MessageLedgerEntryRepository} no longer implements the interface.
 * The library-supplied {@code JpaLedgerEntryRepository} carries {@code @Alternative}
 * (no {@code @Priority}) and therefore remains dormant.
 *
 * <p><strong>Sequence number:</strong> {@code save()} is a plain {@code em.persist()}.
 * Sequence assignment stays in {@link LedgerWriteService#record} until it is
 * migrated to {@code LedgerSequenceAllocator} — tracked in qhorus#256.
 *
 * <p>Refs qhorus#253.
 */
@ApplicationScoped
public class LedgerEntryJpaRepository implements LedgerEntryRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        em.persist(entry);
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(final UUID subjectId,
            final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid " +
                        "AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        // em.find() on the abstract base class is correct for JOINED inheritance —
        // Hibernate resolves the concrete subtype automatically.
        return Optional.ofNullable(em.find(LedgerEntry.class, id));
    }

    @Override
    public List<LedgerEntry> listAll() {
        return em.createQuery("SELECT e FROM LedgerEntry e ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.entryType = :type ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("type", io.casehub.ledger.api.model.LedgerEntryType.EVENT)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findEventsByActorId(final String actorId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.entryType = :type AND e.actorId = :actorId " +
                        "ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("type", io.casehub.ledger.api.model.LedgerEntryType.EVENT)
                .setParameter("actorId", actorId)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByActorId(final String actorId,
            final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorId = :aid " +
                        "AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("aid", actorId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole,
            final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :role " +
                        "AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("role", actorRole)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.occurredAt >= :from AND e.occurredAt <= :to " +
                        "ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :eid ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("eid", entryId)
                .getResultList();
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        em.persist(attestation);
        return attestation;
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return em.createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
                .setParameter("entryId", ledgerEntryId)
                .getResultList();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return em.createNamedQuery("LedgerAttestation.findByEntryIds", LedgerAttestation.class)
                .setParameter("entryIds", entryIds)
                .getResultList()
                .stream()
                .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(
            final UUID ledgerEntryId, final String capabilityTag) {
        return em.createNamedQuery("LedgerAttestation.findByEntryIdAndCapabilityTag", LedgerAttestation.class)
                .setParameter("entryId", ledgerEntryId)
                .setParameter("capabilityTag", capabilityTag)
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(final UUID ledgerEntryId) {
        return em.createNamedQuery("LedgerAttestation.findGlobalByEntryId", LedgerAttestation.class)
                .setParameter("entryId", ledgerEntryId)
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(
            final String attestorId, final String capabilityTag) {
        return em.createNamedQuery("LedgerAttestation.findByAttestorIdAndCapabilityTag", LedgerAttestation.class)
                .setParameter("attestorId", attestorId)
                .setParameter("capabilityTag", capabilityTag)
                .getResultList();
    }
}

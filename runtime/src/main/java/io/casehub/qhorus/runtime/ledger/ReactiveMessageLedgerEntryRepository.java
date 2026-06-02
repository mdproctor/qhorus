package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.privacy.ActorIdentityProvider;
import io.casehub.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link MessageLedgerEntryRepository}.
 *
 * <p>
 * Active when {@code casehub.qhorus.reactive.enabled=true}; excluded from CDI by
 * {@code QhorusProcessor} otherwise. Tests are {@code @Disabled} in CI (requires
 * PostgreSQL reactive driver, not H2).
 *
 * <p>
 * {@link LedgerAttestation} entities are in the {@code qhorus} named PU
 * (via {@code casehub.ledger.datasource=qhorus}) — persisted via the Panache
 * repo session. Attestation reads use {@link LedgerAttestation} named queries.
 *
 * <p>
 * {@link ActorIdentityProvider#tokenise} is called synchronously before the
 * reactive persist. Custom implementations must be non-blocking when paired with
 * the reactive stack; the platform default is pass-through.
 *
 * <p>
 * Refs casehubio/ledger#105, qhorus#234, Epic #99.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveMessageLedgerEntryRepository implements ReactiveLedgerEntryRepository {

    @Inject
    MessageReactivePanacheRepo repo;

    @Inject
    ActorIdentityProvider actorIdentityProvider;

    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry) {
        return repo.persist((MessageLedgerEntry) entry).map(e -> (LedgerEntry) e);
    }

    /**
     * All entries for a channel, ordered by sequence number ascending.
     */
    public Uni<List<MessageLedgerEntry>> findByChannelId(final UUID channelId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", channelId);
    }

    /**
     * Returns the most recent COMMAND or HANDOFF entry with the given correlationId.
     * Used at write time to resolve {@code causedByEntryId}.
     */
    public Uni<Optional<MessageLedgerEntry>> findLatestByCorrelationId(final UUID channelId,
            final String correlationId) {
        return repo.find(
                "subjectId = ?1 AND correlationId = ?2 AND messageType IN ('COMMAND','HANDOFF') " +
                        "ORDER BY sequenceNumber DESC",
                channelId, correlationId)
                .firstResult()
                .map(Optional::ofNullable);
    }

    /**
     * Returns the earliest entry in a correlation thread with a non-null {@code subjectId}.
     * Cross-channel by design — scoped only to {@code correlationId}. Used at write time to
     * propagate the domain subject from the originating COMMAND to all subsequent messages.
     *
     * <p>Ordered by {@code occurredAt ASC, id ASC} — wall-clock order with UUID tiebreaker.
     */
    public Uni<Optional<MessageLedgerEntry>> findEarliestWithSubjectByCorrelationId(
            final String correlationId) {
        return repo.find(
                "correlationId = ?1 AND subjectId IS NOT NULL ORDER BY occurredAt ASC, id ASC",
                correlationId)
                .firstResult()
                .map(Optional::ofNullable);
    }

    /**
     * Returns the ledger entry whose {@code messageId} matches the given surrogate PK.
     * Used at write time to resolve {@code causedByEntryId} from {@code inReplyTo}.
     */
    public Uni<Optional<MessageLedgerEntry>> findByMessageId(final Long messageId) {
        return repo.find("messageId = ?1", messageId)
                .firstResult()
                .map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId) {
        return repo.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResult()
                .map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id) {
        return repo.findById(id).map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> listAll() {
        return repo.listAll().map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findAllEvents() {
        return repo.list("entryType = ?1", LedgerEntryType.EVENT)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorId(final String actorId, final Instant from, final Instant to) {
        return repo.list(
                "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return repo.list(
                "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectIdAndTimeRange(final UUID subjectId, final Instant from, final Instant to) {
        return repo.list("subjectId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC", subjectId, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByTimeRange(final Instant from, final Instant to) {
        return repo.list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC", from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId) {
        return repo.list("causedByEntryId = ?1 ORDER BY sequenceNumber ASC", entryId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation) {
        if (attestation.attestorId != null) {
            attestation.attestorId = actorIdentityProvider.tokenise(attestation.attestorId);
        }
        return repo.getSession()
                .flatMap(session -> session.persist(attestation).replaceWith(attestation));
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
                        .setParameter("entryId", ledgerEntryId)
                        .getResultList());
    }

    @Override
    public Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByEntryIds", LedgerAttestation.class)
                        .setParameter("entryIds", entryIds)
                        .getResultList())
                .map(list -> list.stream().collect(
                        Collectors.groupingBy(a -> a.ledgerEntryId)));
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(final UUID ledgerEntryId,
            final String capabilityTag) {
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByEntryIdAndCapabilityTag",
                                LedgerAttestation.class)
                        .setParameter("entryId", ledgerEntryId)
                        .setParameter("capabilityTag", capabilityTag)
                        .getResultList());
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryIdGlobal(final UUID ledgerEntryId) {
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findGlobalByEntryId", LedgerAttestation.class)
                        .setParameter("entryId", ledgerEntryId)
                        .getResultList());
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(final String attestorId,
            final String capabilityTag) {
        return repo.getSession()
                .flatMap(session -> session
                        .createNamedQuery("LedgerAttestation.findByAttestorIdAndCapabilityTag",
                                LedgerAttestation.class)
                        .setParameter("attestorId", actorIdentityProvider.tokeniseForQuery(attestorId))
                        .setParameter("capabilityTag", capabilityTag)
                        .getResultList());
    }
}

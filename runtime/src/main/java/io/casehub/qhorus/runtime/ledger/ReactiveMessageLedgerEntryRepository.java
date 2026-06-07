package io.casehub.qhorus.runtime.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive qhorus-scoped repository — all queries target {@code MessageLedgerEntry} only.
 *
 * <p>These methods serve qhorus-specific reactive concerns: causal chain resolution,
 * inReplyTo lookup, and subject propagation for qhorus messages. They must not be used
 * for cross-dtype operations.
 *
 * <p>Cross-dtype reactive operations now live in {@link ReactiveLedgerEntryJpaRepository}.
 *
 * <p>The {@code @IfBuildProperty} gate is mandatory: this class injects
 * {@link MessageReactivePanacheRepo}, which is itself gated. Without the gate, CDI
 * build-time validation fails with an unsatisfied injection point in non-reactive builds.
 *
 * <p>Refs qhorus#253.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveMessageLedgerEntryRepository {

    @Inject
    MessageReactivePanacheRepo repo;

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
     * Intentionally qhorus-scoped: {@code correlationId} is a qhorus field not present on
     * the {@link io.casehub.ledger.runtime.model.LedgerEntry} base class.
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

    /** All entries for a channel, ordered by sequence number ascending. */
    public Uni<List<MessageLedgerEntry>> findByChannelId(final UUID channelId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", channelId);
    }
}

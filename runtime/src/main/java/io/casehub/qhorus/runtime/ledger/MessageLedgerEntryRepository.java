package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * Blocking JPA repository for {@link MessageLedgerEntry} — qhorus-scoped queries only.
 *
 * <p>All queries here are intentionally scoped to {@code FROM MessageLedgerEntry} (dtype
 * {@code QHORUS_MESSAGE}). These methods serve qhorus-specific concerns: channel-scoped
 * message history, obligation resolution, telemetry, and causal chain navigation. They must
 * not be used for cross-dtype operations.
 *
 * <p>Cross-dtype {@link io.casehub.ledger.runtime.repository.LedgerEntryRepository} operations
 * (sequence assignment, entry lookup by ID, subject history) now live in
 * {@link LedgerEntryJpaRepository}.
 *
 * <p>Refs qhorus#253, #101, Epic #99.
 */
@ApplicationScoped
public class MessageLedgerEntryRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    /** All entries for a channel, ordered by sequence number ascending. */
    public List<MessageLedgerEntry> findByChannelId(final UUID channelId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber ASC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .getResultList();
    }

    /**
     * Filtered query for the {@code list_ledger_entries} MCP tool. All parameters except
     * {@code channelId} and {@code limit} are optional (pass null to skip).
     *
     * @param channelId scopes the query to this channel
     * @param messageTypes if non-null/non-empty, only entries whose {@code messageType} is in this set
     * @param afterSequence if non-null, only entries with sequenceNumber &gt; afterSequence
     * @param agentId if non-null/blank, filter by actorId
     * @param since if non-null, filter by occurredAt &gt;= since
     * @param limit max results
     */
    public List<MessageLedgerEntry> listEntries(final UUID channelId, final Set<String> messageTypes,
            final Long afterSequence, final String agentId, final Instant since, final int limit) {
        return listEntries(channelId, messageTypes, afterSequence, agentId, since, null, false, limit);
    }

    /**
     * Extended variant adding optional {@code correlationId} filter and sort direction.
     *
     * @param correlationId if non-null/blank, only entries with this correlationId
     * @param sortDesc if true, ORDER BY sequenceNumber DESC (most recent first)
     */
    public List<MessageLedgerEntry> listEntries(final UUID channelId, final Set<String> messageTypes,
            final Long afterSequence, final String agentId, final Instant since,
            final String correlationId, final boolean sortDesc, final int limit) {

        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = ?1");
        final List<Object> params = new ArrayList<>();
        params.add(channelId);

        if (messageTypes != null && !messageTypes.isEmpty()) {
            jpql.append(" AND e.messageType IN (?").append(params.size() + 1).append(")");
            params.add(messageTypes);
        }
        if (afterSequence != null) {
            jpql.append(" AND e.sequenceNumber > ?").append(params.size() + 1);
            params.add(afterSequence);
        }
        if (agentId != null && !agentId.isBlank()) {
            jpql.append(" AND e.actorId = ?").append(params.size() + 1);
            params.add(agentId);
        }
        if (since != null) {
            jpql.append(" AND e.occurredAt >= ?").append(params.size() + 1);
            params.add(since);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            jpql.append(" AND e.correlationId = ?").append(params.size() + 1);
            params.add(correlationId);
        }
        jpql.append(sortDesc
                ? " ORDER BY e.sequenceNumber DESC"
                : " ORDER BY e.sequenceNumber ASC");

        final TypedQuery<MessageLedgerEntry> query = em.createQuery(jpql.toString(), MessageLedgerEntry.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * All ledger entries for a given {@code correlationId} on this channel, ordered ASC.
     * Used by {@code get_obligation_chain} and as an alternative to the filtered
     * {@link #listEntries} when no other filters are needed.
     */
    public List<MessageLedgerEntry> findAllByCorrelationId(final UUID channelId,
            final String correlationId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.correlationId = :corr " +
                        "ORDER BY e.sequenceNumber ASC",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("corr", correlationId)
                .getResultList();
    }

    /**
     * Walks {@code causedByEntryId} links upward from {@code entryId} to the root,
     * returning the chain ordered oldest-first (root first, given entry last).
     *
     * <p>
     * Stops at channel boundaries and on cycles (cycle-guard via visited set).
     * Returns an empty list if {@code entryId} does not exist in this channel.
     */
    public List<MessageLedgerEntry> findAncestorChain(final UUID channelId,
            final UUID entryId) {
        final List<MessageLedgerEntry> chain = new ArrayList<>();
        UUID currentId = entryId;
        final Set<UUID> visited = new java.util.HashSet<>();
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            final MessageLedgerEntry entry = em.find(MessageLedgerEntry.class, currentId);
            if (entry == null || !channelId.equals(entry.channelId)) {
                break;
            }
            chain.add(entry);
            currentId = entry.causedByEntryId;
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * COMMAND entries on this channel whose {@code occurredAt} is before {@code olderThan}
     * and which have no terminal sibling (DONE / FAILURE / DECLINE / HANDOFF) sharing
     * the same {@code correlationId}. These are the stalled obligations.
     */
    public List<MessageLedgerEntry> findStalledCommands(final UUID channelId,
            final Instant olderThan) {
        return em.createQuery(
                "SELECT c FROM MessageLedgerEntry c " +
                        "WHERE c.subjectId = :cid " +
                        "AND c.messageType = 'COMMAND' " +
                        "AND c.occurredAt < :olderThan " +
                        "AND NOT EXISTS (" +
                        "  SELECT t FROM MessageLedgerEntry t " +
                        "  WHERE t.subjectId = :cid " +
                        "  AND t.correlationId = c.correlationId " +
                        "  AND t.messageType IN ('DONE', 'FAILURE', 'DECLINE', 'HANDOFF')" +
                        ")",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("olderThan", olderThan)
                .getResultList();
    }

    /**
     * Count of each outcome-relevant message type on this channel.
     * Returns a map containing keys from {@code COMMAND, DONE, FAILURE, DECLINE, HANDOFF}
     * (absent keys mean zero occurrences).
     */
    public java.util.Map<String, Long> countByOutcome(final UUID channelId) {
        final List<Object[]> rows = em.createQuery(
                "SELECT e.messageType, COUNT(e) FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid " +
                        "AND e.messageType IN ('COMMAND', 'DONE', 'FAILURE', 'DECLINE', 'HANDOFF') " +
                        "GROUP BY e.messageType",
                Object[].class)
                .setParameter("cid", channelId)
                .getResultList();
        final java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (final Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }

    /**
     * All entries for {@code actorId} on this channel, ordered by sequence number
     * descending (most recent first), capped at {@code limit}.
     */
    public List<MessageLedgerEntry> findByActorIdInChannel(final UUID channelId,
            final String actorId, final int limit) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.actorId = :aid " +
                        "ORDER BY e.sequenceNumber DESC",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("aid", actorId)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * All EVENT entries on this channel at or after {@code since} (pass null for all).
     * Used by the tool layer to compute per-tool telemetry aggregations in Java.
     */
    public List<MessageLedgerEntry> findEventsSince(final UUID channelId,
            final Instant since) {
        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.messageType = 'EVENT'");
        if (since != null) {
            jpql.append(" AND e.occurredAt >= :since");
        }
        jpql.append(" ORDER BY e.sequenceNumber ASC");
        final TypedQuery<MessageLedgerEntry> q = em.createQuery(jpql.toString(),
                MessageLedgerEntry.class)
                .setParameter("cid", channelId);
        if (since != null) {
            q.setParameter("since", since);
        }
        return q.getResultList();
    }

    /**
     * Returns the most recent COMMAND or HANDOFF entry on this channel with the given
     * correlation ID. Used at write time to resolve {@code causedByEntryId} for DONE,
     * FAILURE, DECLINE, and HANDOFF entries.
     */
    public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
            final String correlationId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :sid AND e.correlationId = :corr " +
                        "AND e.messageType IN ('COMMAND', 'HANDOFF') " +
                        "ORDER BY e.sequenceNumber DESC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .setParameter("corr", correlationId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Finds the ledger entry whose {@code messageId} matches the given message entity ID.
     * Used at ledger write time to resolve {@code causedByEntryId} from {@code inReplyTo}.
     *
     * <p>{@code messageId} is the surrogate Long PK of the {@code Message} entity — unique
     * within the qhorus datasource by construction.
     */
    public Optional<MessageLedgerEntry> findByMessageId(final Long messageId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.messageId = :mid",
                MessageLedgerEntry.class)
                .setParameter("mid", messageId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Returns the earliest entry in a correlation thread that has a non-null {@code subjectId}.
     * Used at write time to propagate the domain subject ({@code subjectId}) from the originating
     * COMMAND to all subsequent messages in the same correlation thread.
     *
     * <p>Intentionally qhorus-scoped ({@code FROM MessageLedgerEntry}): {@code correlationId}
     * is a qhorus field not present on the {@link io.casehub.ledger.runtime.model.LedgerEntry}
     * base class. Only qhorus entries participate in correlation threads.
     *
     * <p>Cross-channel by design — scoped only to {@code correlationId}, not to a channel.
     */
    public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(
            final String correlationId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.correlationId = :corr AND e.subjectId IS NOT NULL " +
                        "ORDER BY e.occurredAt ASC, e.id ASC",
                MessageLedgerEntry.class)
                .setParameter("corr", correlationId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Cross-channel query for {@code get_obligation_activity}. Returns all entries whose
     * {@code correlationId} exactly matches, ordered chronologically across all channels.
     */
    public List<MessageLedgerEntry> findByCorrelationIdAcrossChannels(
            final String correlationId, final int limit) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.correlationId = :corrId " +
                        "ORDER BY e.messageId ASC",
                MessageLedgerEntry.class)
                .setParameter("corrId", correlationId)
                .setMaxResults(limit)
                .getResultList();
    }
}

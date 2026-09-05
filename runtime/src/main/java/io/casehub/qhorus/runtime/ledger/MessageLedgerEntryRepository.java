package io.casehub.qhorus.runtime.ledger;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.judgment.JudgmentEventKinds;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * {@link QhorusLedgerEntryRepository}.
 *
 * <p>All query methods accept a {@code tenancyId} parameter and scope results to that tenant.
 * Null {@code tenancyId} is normalised to {@link TenancyConstants#DEFAULT_TENANT_ID} by
 * {@link #tenancyId(String)}. Callers obtain {@code tenancyId} from either
 * {@code dispatch.tenancyId()} (service layer) or {@code currentPrincipal.tenancyId()} (MCP tools).
 *
 * <p><strong>Known limitation — cross-tenant delegation:</strong> {@link
 * #findEarliestWithSubjectByCorrelationId} and {@link #findByCorrelationIdAcrossChannels} are
 * now tenant-scoped. A HANDOFF that delegates to an agent in a different tenant will silently
 * lose {@code subjectId} propagation at the tenant boundary. This is acceptable for current
 * use cases (cross-tenant delegation not yet supported). See qhorus#265 design spec.
 *
 * <p>Unchanged (surrogate PK — unique within datasource, no tenant ambiguity):
 * {@link #findByMessageId}, {@link #findByMessageIds}.
 *
 * <p>Refs qhorus#253, #101, #263, Epic #99.
 */
@ApplicationScoped
public class MessageLedgerEntryRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    /** All entries for a channel in {@code tenancyId}, ordered by sequence number ascending. */
    public List<MessageLedgerEntry> findByChannelId(final UUID channelId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :sid AND e.tenancyId = :tid" +
                " ORDER BY e.sequenceNumber ASC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    /**
     * Filtered query for the {@code list_ledger_entries} MCP tool. All parameters except
     * {@code channelId}, {@code limit}, and {@code tenancyId} are optional (pass null to skip).
     *
     * @param channelId scopes the query to this channel
     * @param messageTypes if non-null/non-empty, only entries whose {@code messageType} is in this set
     * @param afterSequence if non-null, only entries with sequenceNumber > afterSequence
     * @param agentId if non-null/blank, filter by actorId
     * @param since if non-null, filter by occurredAt >= since
     * @param limit max results
     * @param tenancyId scopes the query to this tenant
     */
    public List<MessageLedgerEntry> listEntries(final UUID channelId, final Set<String> messageTypes,
            final Long afterSequence, final String agentId, final Instant since,
            final int limit, final String tenancyId) {
        return listEntries(channelId, messageTypes, afterSequence, agentId, since, null, false, limit, tenancyId);
    }

    /**
     * Extended variant adding optional {@code correlationId} filter and sort direction.
     *
     * @param correlationId if non-null/blank, only entries with this correlationId
     * @param sortDesc if true, ORDER BY sequenceNumber DESC (most recent first)
     * @param tenancyId scopes the query to this tenant; null falls back to DEFAULT_TENANT_ID
     */
    public List<MessageLedgerEntry> listEntries(final UUID channelId, final Set<String> messageTypes,
            final Long afterSequence, final String agentId, final Instant since,
            final String correlationId, final boolean sortDesc, final int limit, final String tenancyId) {

        // tenancyId is the second fixed parameter; dynamic params start at index 3.
        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = ?1 AND e.tenancyId = ?2");
        final List<Object> params = new ArrayList<>();
        params.add(channelId);
        params.add(tenancyId(tenancyId));

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
     * All ledger entries for a given {@code correlationId} on this channel in {@code tenancyId},
     * ordered ASC. Used by {@code get_obligation_chain} and as an alternative to the filtered
     * {@link #listEntries} when no other filters are needed.
     */
    public List<MessageLedgerEntry> findAllByCorrelationId(final UUID channelId,
            final String correlationId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.correlationId = :corr AND e.tenancyId = :tid " +
                        "ORDER BY e.sequenceNumber ASC",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("corr", correlationId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
    }

    /**
     * Walks {@code causedByEntryId} links upward from {@code entryId} to the root,
     * returning the chain ordered oldest-first (root first, given entry last).
     *
     * <p>Stops at channel or tenant boundaries and on cycles (cycle-guard via visited set).
     * Returns an empty list if {@code entryId} does not exist in this channel and tenant.
     */
    public List<MessageLedgerEntry> findAncestorChain(final UUID channelId,
            final UUID entryId, final String tenancyId) {
        final List<MessageLedgerEntry> chain = new ArrayList<>();
        UUID currentId = entryId;
        final Set<UUID> visited = new java.util.HashSet<>();
        final String tid = tenancyId(tenancyId);
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            final MessageLedgerEntry entry = em.find(MessageLedgerEntry.class, currentId);
            if (entry == null || !channelId.equals(entry.channelId) || !tid.equals(entry.tenancyId)) {
                break;
            }
            chain.add(entry);
            currentId = entry.causedByEntryId;
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Cross-channel variant of {@link #findAncestorChain} — walks {@code causedByEntryId}
     * links upward from {@code entryId} to the root without stopping at channel boundaries.
     * Only the tenancy boundary is enforced (security boundary).
     * <p>
     * <p>Depth limit: 50 hops. Cycle-guard via visited set.
     * Returns an empty list if {@code entryId} does not exist in this tenant.
     */
    public List<MessageLedgerEntry> findAncestorChainCrossChannel(
            final UUID entryId, final String tenancyId) {
        final List<MessageLedgerEntry> chain     = new ArrayList<>();
        UUID                           currentId = entryId;
        final Set<UUID>                visited   = new java.util.HashSet<>();
        final String                   tid       = tenancyId(tenancyId);
        int                            depth     = 0;
        while (currentId != null && !visited.contains(currentId) && depth < 50) {
            visited.add(currentId);
            final MessageLedgerEntry entry = em.find(MessageLedgerEntry.class, currentId);
            if (entry == null || !tid.equals(entry.tenancyId)) {
                break;
            }
            chain.add(entry);
            currentId = entry.causedByEntryId;
            depth++;
        }
        Collections.reverse(chain);
        return chain;
    }


    /**
     * COMMAND entries on this channel in {@code tenancyId} whose {@code occurredAt} is before
     * {@code olderThan} and which have no terminal sibling (DONE / FAILURE / DECLINE / HANDOFF)
     * sharing the same {@code correlationId}. These are the stalled obligations.
     */
    public List<MessageLedgerEntry> findStalledCommands(final UUID channelId,
            final Instant olderThan, final String tenancyId) {
        return em.createQuery(
                "SELECT c FROM MessageLedgerEntry c " +
                        "WHERE c.subjectId = :cid " +
                        "AND c.tenancyId = :tid " +
                        "AND c.messageType = 'COMMAND' " +
                        "AND c.occurredAt < :olderThan " +
                        "AND NOT EXISTS (" +
                        "  SELECT t FROM MessageLedgerEntry t " +
                        "  WHERE t.subjectId = :cid " +
                        "  AND t.tenancyId = :tid " +
                        "  AND t.correlationId = c.correlationId " +
                        "  AND t.messageType IN ('DONE', 'FAILURE', 'DECLINE', 'HANDOFF')" +
                        ")",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("tid", tenancyId(tenancyId))
                .setParameter("olderThan", olderThan)
                .getResultList();
    }

    /**
     * Count of each outcome-relevant message type on this channel in {@code tenancyId}.
     * Returns a map containing keys from {@code COMMAND, DONE, FAILURE, DECLINE, HANDOFF}
     * (absent keys mean zero occurrences).
     */
    public java.util.Map<String, Long> countByOutcome(final UUID channelId, final String tenancyId) {
        final List<Object[]> rows = em.createQuery(
                "SELECT e.messageType, COUNT(e) FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid " +
                        "AND e.tenancyId = :tid " +
                        "AND e.messageType IN ('COMMAND', 'DONE', 'FAILURE', 'DECLINE', 'HANDOFF') " +
                        "GROUP BY e.messageType",
                Object[].class)
                .setParameter("cid", channelId)
                .setParameter("tid", tenancyId(tenancyId))
                .getResultList();
        final java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (final Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }

    public java.util.Map<String, Long> countByOutcome(final UUID channelId, final java.time.Instant from, final java.time.Instant to, final String tenancyId) {
        final List<Object[]> rows = em.createQuery(
                                              "SELECT e.messageType, COUNT(e) FROM MessageLedgerEntry e " +
                                              "WHERE e.subjectId = :cid " +
                                              "AND e.tenancyId = :tid " +
                                              "AND e.occurredAt >= :from " +
                                              "AND e.occurredAt <= :to " +
                                              "AND e.messageType IN ('COMMAND', 'DONE', 'FAILURE', 'DECLINE', 'HANDOFF') " +
                                              "GROUP BY e.messageType",
                                              Object[].class)
                                      .setParameter("cid", channelId)
                                      .setParameter("tid", tenancyId(tenancyId))
                                      .setParameter("from", from)
                                      .setParameter("to", to)
                                      .getResultList();
        final java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (final Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }


    /**
     * All entries for {@code actorId} on this channel in {@code tenancyId}, ordered by
     * sequence number descending (most recent first), capped at {@code limit}.
     */
    public List<MessageLedgerEntry> findByActorIdInChannel(final UUID channelId,
            final String actorId, final int limit, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.actorId = :aid AND e.tenancyId = :tid " +
                        "ORDER BY e.sequenceNumber DESC",
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("aid", actorId)
                .setParameter("tid", tenancyId(tenancyId))
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * All EVENT entries on this channel in {@code tenancyId} at or after {@code since} (pass null
     * for all). Used by the tool layer to compute per-tool telemetry aggregations in Java.
     */
    public List<MessageLedgerEntry> findEventsSince(final UUID channelId,
            final Instant since, final String tenancyId) {
        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :cid AND e.messageType = 'EVENT' AND e.tenancyId = :tid");
        if (since != null) {
            jpql.append(" AND e.occurredAt >= :since");
        }
        jpql.append(" ORDER BY e.sequenceNumber ASC");
        final TypedQuery<MessageLedgerEntry> q = em.createQuery(jpql.toString(),
                MessageLedgerEntry.class)
                .setParameter("cid", channelId)
                .setParameter("tid", tenancyId(tenancyId));
        if (since != null) {
            q.setParameter("since", since);
        }
        return q.getResultList();
    }

    /**
     * Returns the most recent COMMAND or HANDOFF entry on this channel in {@code tenancyId} with
     * the given correlation ID. Used at write time to resolve {@code causedByEntryId}.
     */
    public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
            final String correlationId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :sid AND e.correlationId = :corr " +
                        "AND e.tenancyId = :tid " +
                        "AND e.messageType IN ('COMMAND', 'HANDOFF') " +
                        "ORDER BY e.sequenceNumber DESC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .setParameter("corr", correlationId)
                .setParameter("tid", tenancyId(tenancyId))
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    public Optional<MessageLedgerEntry> findTerminalEntryByCorrelationId(
            final UUID channelId, final String correlationId, final String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e " +
                         "WHERE e.subjectId = :sid AND e.correlationId = :corr " +
                         "AND e.tenancyId = :tid " +
                         "AND e.messageType IN ('DONE', 'FAILURE', 'DECLINE') " +
                         "ORDER BY e.sequenceNumber DESC",
                         MessageLedgerEntry.class)
                 .setParameter("sid", channelId)
                 .setParameter("corr", correlationId)
                 .setParameter("tid", tenancyId(tenancyId))
                 .setMaxResults(1)
                 .getResultStream()
                 .findFirst();
    }


    /**
     * Finds the ledger entry whose {@code messageId} matches the given message entity ID.
     * Used at ledger write time to resolve {@code causedByEntryId} from {@code inReplyTo}.
     *
     * <p>{@code messageId} is the surrogate Long PK of the {@code Message} entity — unique
     * within the qhorus datasource by construction. No tenant parameter needed.
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
     * Returns the earliest entry in a correlation thread in {@code tenancyId} with a non-null
     * {@code subjectId}. Used at write time to propagate the domain subject from the originating
     * COMMAND to all subsequent messages in the same correlation thread.
     *
     * <p>Cross-channel by design — scoped only to {@code correlationId} and {@code tenancyId}.
     *
     * <p><strong>Known limitation:</strong> cross-tenant HANDOFF delegation silently loses
     * subjectId propagation at the tenant boundary. See qhorus#265 design spec for rationale.
     */
    public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(
            final String correlationId, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.correlationId = :corr AND e.subjectId IS NOT NULL " +
                        "AND e.tenancyId = :tid " +
                        "ORDER BY e.occurredAt ASC, e.id ASC",
                MessageLedgerEntry.class)
                .setParameter("corr", correlationId)
                .setParameter("tid", tenancyId(tenancyId))
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Batch lookup by message ID collection — one {@code IN} query replacing per-EVENT
     * individual {@code findByMessageId} calls in {@code getChannelTimeline()}. Refs #262.
     *
     * <p>Returns an empty list when {@code messageIds} is empty, avoiding a malformed
     * {@code IN ()} clause on databases that don't tolerate it.
     *
     * <p>No tenant parameter — queries by surrogate PK which is unique within the datasource.
     */
    public List<MessageLedgerEntry> findByMessageIds(final java.util.Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.messageId IN :ids",
                MessageLedgerEntry.class)
                .setParameter("ids", messageIds)
                .getResultList();
    }

    /**
     * Cross-channel query for {@code get_obligation_activity} within {@code tenancyId}.
     * Returns all entries whose {@code correlationId} exactly matches, ordered chronologically
     * across all channels in the same tenant.
     *
     * <p><strong>Known limitation:</strong> cross-tenant delegation traces break at the tenant
     * boundary. See qhorus#265 design spec.
     */
    public List<MessageLedgerEntry> findByCorrelationIdAcrossChannels(
            final String correlationId, final int limit, final String tenancyId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.correlationId = :corrId AND e.tenancyId = :tid " +
                        "ORDER BY e.messageId ASC",
                MessageLedgerEntry.class)
                .setParameter("corrId", correlationId)
                .setParameter("tid", tenancyId(tenancyId))
                .setMaxResults(limit)
                .getResultList();
    }


    public List<MessageLedgerEntry> findJudgmentEvents(
            final UUID channelId, final UUID judgmentId,
            final Instant from, final Instant to, final String tenancyId) {
        StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e WHERE e.tenancyId = :tid AND e.toolName IN :kinds");
        if (channelId != null) {jpql.append(" AND e.channelId = :channelId");}
        if (judgmentId != null) {jpql.append(" AND e.judgmentId = :judgmentId");}
        if (from != null) {jpql.append(" AND e.occurredAt >= :from");}
        if (to != null) {jpql.append(" AND e.occurredAt <= :to");}
        jpql.append(" ORDER BY e.occurredAt ASC");

        TypedQuery<MessageLedgerEntry> query = em.createQuery(jpql.toString(), MessageLedgerEntry.class)
                                                 .setParameter("tid", tenancyId(tenancyId))
                                                 .setParameter("kinds", JudgmentEventKinds.ALL);
        if (channelId != null) {query.setParameter("channelId", channelId);}
        if (judgmentId != null) {query.setParameter("judgmentId", judgmentId);}
        if (from != null) {query.setParameter("from", from);}
        if (to != null) {query.setParameter("to", to);}
        return query.getResultList();
    }

    public List<Object[]> countJudgmentOutcomes(final Instant from, final Instant to, final String tenancyId) {
        return em.createQuery(
                         "SELECT e.judgmentType, e.toolName, e.verificationOutcome, COUNT(e)"
                         + " FROM MessageLedgerEntry e"
                         + " WHERE e.tenancyId = :tid"
                         + " AND e.toolName IN :terminalKinds"
                         + " AND e.occurredAt >= :from AND e.occurredAt <= :to"
                         + " GROUP BY e.judgmentType, e.toolName, e.verificationOutcome", Object[].class)
                 .setParameter("tid", tenancyId(tenancyId))
                 .setParameter("terminalKinds", JudgmentEventKinds.TERMINAL)
                 .setParameter("from", from)
                 .setParameter("to", to)
                 .getResultList();
    }

    public List<MessageLedgerEntry> findPendingJudgments(final String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e WHERE e.tenancyId = :tid"
                         + " AND e.toolName = :yieldedKind"
                         + " AND NOT EXISTS (SELECT 1 FROM MessageLedgerEntry v"
                         + "   WHERE v.tenancyId = :tid"
                         + "   AND v.judgmentId = e.judgmentId"
                         + "   AND v.toolName IN :terminalKinds)"
                         + " ORDER BY e.occurredAt ASC", MessageLedgerEntry.class)
                 .setParameter("tid", tenancyId(tenancyId))
                 .setParameter("yieldedKind", JudgmentEventKinds.YIELDED)
                 .setParameter("terminalKinds", JudgmentEventKinds.TERMINAL)
                 .getResultList();
    }

    private static String tenancyId(final String tenancyId) {
        return tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;
    }

    public List<MessageLedgerEntry> findLatestContextPressure(final UUID channelId, final String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :cid AND e.tenancyId = :tid" +
                         " AND e.messageType = 'EVENT' AND e.contextWindowPct IS NOT NULL" +
                         " AND e.sequenceNumber = (SELECT MAX(e2.sequenceNumber) FROM MessageLedgerEntry e2" +
                         " WHERE e2.subjectId = :cid AND e2.tenancyId = :tid" +
                         " AND e2.messageType = 'EVENT' AND e2.contextWindowPct IS NOT NULL" +
                         " AND e2.actorId = e.actorId)",
                         MessageLedgerEntry.class)
                 .setParameter("cid", channelId)
                 .setParameter("tid", tenancyId(tenancyId))
                 .getResultList();
    }

    public Optional<MessageLedgerEntry> findLatestContextPressureForActor(String actorId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e WHERE e.actorId = :actorId" +
                         " AND e.messageType = 'EVENT' AND e.contextWindowPct IS NOT NULL" +
                         " ORDER BY e.sequenceNumber DESC",
                         MessageLedgerEntry.class)
                 .setParameter("actorId", actorId)
                 .setMaxResults(1)
                 .getResultStream().findFirst();
    }

    public List<MessageLedgerEntry> findLatestContextPressureGlobal() {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e WHERE e.messageType = 'EVENT'" +
                         " AND e.contextWindowPct IS NOT NULL" +
                         " AND e.sequenceNumber = (SELECT MAX(e2.sequenceNumber) FROM MessageLedgerEntry e2" +
                         " WHERE e2.actorId = e.actorId AND e2.messageType = 'EVENT'" +
                         " AND e2.contextWindowPct IS NOT NULL)",
                         MessageLedgerEntry.class)
                 .getResultList();
    }

    public Optional<MessageLedgerEntry> findLatestEntryByActor(String actorId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e WHERE e.actorId = :actorId" +
                         " ORDER BY e.sequenceNumber DESC",
                         MessageLedgerEntry.class)
                 .setParameter("actorId", actorId)
                 .setMaxResults(1)
                 .getResultStream().findFirst();
    }


    public boolean hasJudgmentEvent(String correlationId, String tenancyId) {
        return em.createQuery(
                         "SELECT COUNT(e) FROM MessageLedgerEntry e " +
                         "WHERE e.correlationId = :corrId " +
                         "AND e.tenancyId = :tenancyId " +
                         "AND e.toolName = :yieldedKind", Long.class)
                 .setParameter("corrId", correlationId)
                 .setParameter("tenancyId", tenancyId(tenancyId))
                 .setParameter("yieldedKind", JudgmentEventKinds.YIELDED)
                 .getSingleResult() > 0;
    }

    public List<MessageLedgerEntry> findDoneEntriesWithoutAttestation(Instant from, Instant to, String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e " +
                         "WHERE e.tenancyId = :tenancyId " +
                         "AND e.messageType = 'DONE' " +
                         "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                         "AND NOT EXISTS (SELECT 1 FROM " +
                         "io.casehub.ledger.runtime.model.LedgerAttestation a " +
                         "WHERE a.ledgerEntryId = e.id) " +
                         "AND NOT EXISTS (SELECT 1 FROM MessageLedgerEntry v " +
                         "WHERE v.correlationId = e.correlationId " +
                         "AND v.tenancyId = :tenancyId " +
                         "AND v.toolName = :yieldedKind)",
                         MessageLedgerEntry.class)
                 .setParameter("tenancyId", tenancyId(tenancyId))
                 .setParameter("from", from)
                 .setParameter("to", to)
                 .setParameter("yieldedKind",
                               io.casehub.qhorus.api.judgment.JudgmentEventKinds.YIELDED)
                 .getResultList();
    }

    public List<MessageLedgerEntry> findRoutingEntries(Instant from, Instant to, String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e " +
                         "WHERE e.tenancyId = :tenancyId " +
                         "AND e.routingSelectedAgent IS NOT NULL " +
                         "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                         "ORDER BY e.occurredAt ASC",
                         MessageLedgerEntry.class)
                 .setParameter("tenancyId", tenancyId(tenancyId))
                 .setParameter("from", from)
                 .setParameter("to", to)
                 .getResultList();
    }

    public List<MessageLedgerEntry> findHandoffEntries(Instant from, Instant to, String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e " +
                         "WHERE e.tenancyId = :tenancyId " +
                         "AND e.messageType = 'HANDOFF' " +
                         "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
                         "ORDER BY e.occurredAt ASC",
                         MessageLedgerEntry.class)
                 .setParameter("tenancyId", tenancyId(tenancyId))
                 .setParameter("from", from)
                 .setParameter("to", to)
                 .getResultList();
    }


    public List<MessageLedgerEntry> findDoneEntriesWithDeferredAttestation(String tenancyId) {
        return em.createQuery(
                         "SELECT e FROM MessageLedgerEntry e " +
                         "WHERE e.tenancyId = :tenancyId " +
                         "AND e.messageType = 'DONE' " +
                         "AND NOT EXISTS (SELECT 1 FROM io.casehub.ledger.runtime.model.LedgerAttestation a " +
                         "WHERE a.ledgerEntryId = e.id) " +
                         "AND EXISTS (SELECT 1 FROM MessageLedgerEntry v " +
                         "WHERE v.correlationId = e.correlationId " +
                         "AND v.tenancyId = :tenancyId " +
                         "AND v.toolName = :verifiedKind)",
                         MessageLedgerEntry.class)
                 .setParameter("tenancyId", tenancyId(tenancyId))
                 .setParameter("verifiedKind",
                               io.casehub.qhorus.api.judgment.JudgmentEventKinds.VERIFIED)
                 .getResultList();
    }


}

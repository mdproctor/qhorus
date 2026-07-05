package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link MessageLedgerEntryRepository}.
 * Refs #101 — Epic #99.
 */
@QuarkusTest
@TestTransaction
class MessageLedgerEntryRepositoryTest {

    @Inject
    MessageLedgerEntryRepository repo;

    @Inject
    LedgerEntryRepository ledger; // cross-dtype save + findLatestBySubjectId

    @Test
    void save_andFindByChannelId_returnsSavedEntry() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        List<MessageLedgerEntry> found = repo.findByChannelId(channelId, null);
        assertEquals(1, found.size());
        assertEquals("COMMAND", found.get(0).messageType);
        assertEquals(channelId, found.get(0).channelId);
    }

    @Test
    void findByChannelId_unknownChannel_returnsEmpty() {
        assertTrue(repo.findByChannelId(UUID.randomUUID(), null).isEmpty());
    }

    @Test
    void findByChannelId_orderedBySequenceNumber() {
        UUID channelId = UUID.randomUUID();
        // Saves in insert order; QhorusSequenceAllocator assigns seq 1, 2, 3 in that order.
        // findByChannelId orders by sequenceNumber ASC — verifies the ordering guarantee.
        ledger.save(entry(channelId, 3L, "DONE"), null);
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        ledger.save(entry(channelId, 2L, "STATUS"), null);
        List<MessageLedgerEntry> found = repo.findByChannelId(channelId, null);
        assertEquals(3, found.size());
        assertEquals(1, found.get(0).sequenceNumber);
        assertEquals(2, found.get(1).sequenceNumber);
        assertEquals(3, found.get(2).sequenceNumber);
    }

    @Test
    void findLatestBySubjectId_returnsHighestSequenceEntry() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        ledger.save(entry(channelId, 2L, "DONE"), null);
        var latest = ledger.findLatestBySubjectId(channelId, null);
        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().sequenceNumber);
    }

    @Test
    void findLatestBySubjectId_emptyChannel_returnsEmpty() {
        assertTrue(ledger.findLatestBySubjectId(UUID.randomUUID(), null).isEmpty());
    }

    @Test
    void listEntries_noFilter_returnsAllTypes() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        ledger.save(entry(channelId, 2L, "STATUS"), null);
        ledger.save(entry(channelId, 3L, "DONE"), null);
        ledger.save(entry(channelId, 4L, "EVENT"), null);
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, null, null, 20, null);
        assertEquals(4, entries.size());
    }

    @Test
    void listEntries_typeFilter_returnsOnlyMatchingTypes() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        ledger.save(entry(channelId, 2L, "DONE"), null);
        ledger.save(entry(channelId, 3L, "EVENT"), null);
        ledger.save(entry(channelId, 4L, "DECLINE"), null);
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, Set.of("COMMAND", "DONE"), null, null, null, 20, null);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> Set.of("COMMAND", "DONE").contains(e.messageType)));
    }

    @Test
    void listEntries_typeFilter_noMatch_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, Set.of("DONE"), null, null, null, 20, null);
        assertTrue(entries.isEmpty());
    }

    @Test
    void listEntries_afterSequence_returnsOnlyLaterEntries() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, 1L, "COMMAND"), null);
        ledger.save(entry(channelId, 2L, "STATUS"), null);
        ledger.save(entry(channelId, 3L, "DONE"), null);
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, 1L, null, null, 20, null);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.sequenceNumber > 1));
    }

    @Test
    void listEntries_agentFilter_returnsOnlyMatchingAgent() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry e1 = entry(channelId, 1L, "COMMAND");
        e1.actorId = "agent-a";
        MessageLedgerEntry e2 = entry(channelId, 2L, "DONE");
        e2.actorId = "agent-b";
        ledger.save(e1, null);
        ledger.save(e2, null);
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, "agent-a", null, 20, null);
        assertEquals(1, entries.size());
        assertEquals("agent-a", entries.get(0).actorId);
    }

    @Test
    void listEntries_sinceFilter_excludesOlderEntries() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry old = entry(channelId, 1L, "COMMAND");
        old.occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        ledger.save(old, null);
        MessageLedgerEntry recent = entry(channelId, 2L, "DONE");
        recent.occurredAt = Instant.parse("2026-06-01T00:00:00Z");
        ledger.save(recent, null);
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, null, null, null, Instant.parse("2026-03-01T00:00:00Z"), 20, null);
        assertEquals(1, entries.size());
        assertEquals("DONE", entries.get(0).messageType);
    }

    @Test
    void listEntries_limit_capsResults() {
        UUID channelId = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            ledger.save(entry(channelId, (long) i, "EVENT"), null);
        }
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, null, null, 3, null);
        assertEquals(3, entries.size());
    }

    @Test
    void findLatestByCorrelationId_returnsCommandEntry() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry cmd = entry(channelId, 1L, "COMMAND");
        cmd.correlationId = "corr-1";
        ledger.save(cmd, null);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-1", null);
        assertTrue(found.isPresent());
        assertEquals("COMMAND", found.get().messageType);
    }

    @Test
    void findLatestByCorrelationId_withHandoff_returnsLatestHandoff() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry cmd = entry(channelId, 1L, "COMMAND");
        cmd.correlationId = "corr-2";
        ledger.save(cmd, null);
        MessageLedgerEntry handoff = entry(channelId, 2L, "HANDOFF");
        handoff.correlationId = "corr-2";
        ledger.save(handoff, null);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-2", null);
        assertTrue(found.isPresent());
        assertEquals("HANDOFF", found.get().messageType);
    }

    @Test
    void findLatestByCorrelationId_doneEntryIgnored() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry done = entry(channelId, 1L, "DONE");
        done.correlationId = "corr-3";
        ledger.save(done, null);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-3", null);
        assertTrue(found.isEmpty());
    }

    @Test
    void findLatestByCorrelationId_unknownCorrelationId_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        assertTrue(repo.findLatestByCorrelationId(channelId, "no-such-corr", null).isEmpty());
    }

    // ── cross-tenant isolation ────────────────────────────────────────────────

    @Test
    void findByChannelId_tenantScoped_excludesOtherTenant() {
        UUID channelId = UUID.randomUUID();
        // Entry saved with DEFAULT_TENANT_ID (null → normalised)
        ledger.save(entry(channelId, -1001L, "COMMAND"), null);

        // Query with same tenant → found
        List<MessageLedgerEntry> found = repo.findByChannelId(channelId, null);
        assertEquals(1, found.size());

        // Query with different tenant → not found (tenant isolation works)
        List<MessageLedgerEntry> notFound = repo.findByChannelId(channelId, "tenant-other");
        assertTrue(notFound.isEmpty());
    }

    @Test
    void listEntries_tenantScoped_excludesOtherTenant() {
        UUID channelId = UUID.randomUUID();
        ledger.save(entry(channelId, -1002L, "COMMAND"), null);

        // Same tenant → found
        assertEquals(1, repo.listEntries(channelId, null, null, null, null, 10, null).size());

        // Different tenant → not found
        assertTrue(repo.listEntries(channelId, null, null, null, null, 10, "tenant-other").isEmpty());
    }

    /**
     * sequenceNumber is NOT set here — QhorusSequenceAllocator assigns it atomically in
     * save(). Any caller-supplied value would be silently overwritten. Refs #256.
     */
    private MessageLedgerEntry entry(final UUID channelId, final long messageId, final String type) {
        MessageLedgerEntry e = new MessageLedgerEntry();
        e.channelId = channelId;
        e.subjectId = channelId;
        e.messageId = messageId;
        e.messageType = type;
        e.actorId = "agent-1";
        e.actorType = ActorType.AGENT;
        e.entryType = LedgerEntryType.EVENT;
        e.occurredAt = Instant.now();
        return e;
    }
}

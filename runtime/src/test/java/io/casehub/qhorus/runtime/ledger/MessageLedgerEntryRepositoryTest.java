package io.casehub.qhorus.runtime.ledger;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageLedgerEntryRepositoryTest {

    @Inject
    LedgerEntryRepository ledger; // cross-dtype save

    @Inject
    MessageLedgerEntryRepository repository;

    // Cannot use MessageLedgerEntryTestFactory (casehub-qhorus-testing) — adding that module
    // as a test dep would create a build cycle: runtime → test → testing → compile → runtime.
    private static MessageLedgerEntry buildEntry(UUID subjectId, Long messageId,
            String messageType, UUID channelId, String correlationId) {
        MessageLedgerEntry e = new MessageLedgerEntry();
        e.subjectId = subjectId;
        e.channelId = channelId;
        e.messageId = messageId;
        e.messageType = messageType;
        e.correlationId = correlationId;
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.COMMAND;
        e.actorId = "test-actor";
        e.actorType = ActorType.AGENT;
        e.actorRole = "test-role";
        e.occurredAt = Instant.now();
        e.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
        return e;
    }

    @Test
    @TestTransaction
    void findByMessageId_returns_entry_for_known_messageId() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry e = buildEntry(channelId, 99L, "COMMAND", channelId, "corr-1");
        e.sequenceNumber = 1;
        ledger.save(e, null);

        Optional<MessageLedgerEntry> found = repository.findByMessageId(99L);
        assertThat(found).isPresent();
        assertThat(found.get().messageId).isEqualTo(99L);
    }

    @Test
    @TestTransaction
    void findByMessageId_returns_empty_for_unknown_messageId() {
        assertThat(repository.findByMessageId(-999L)).isEmpty();
    }

    @Test
    @TestTransaction
    void findEarliestWithSubjectByCorrelationId_returns_first_by_sequenceNumber() {
        UUID subjectId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        // Use a unique correlationId to avoid cross-test contamination from stale
        // REQUIRES_NEW ledger entries committed by other tests using "corr-x".
        String corrId = "corr-repo-test-" + UUID.randomUUID();

        // seq 1 — first entry
        MessageLedgerEntry first = buildEntry(subjectId, 1L, "COMMAND", channelId, corrId);
        first.sequenceNumber = 1;
        ledger.save(first, null);

        // seq 2 — later entry, same correlation, same subject
        MessageLedgerEntry second = buildEntry(subjectId, 2L, "STATUS", channelId, corrId);
        second.sequenceNumber = 2;
        ledger.save(second, null);

        Optional<MessageLedgerEntry> found =
                repository.findEarliestWithSubjectByCorrelationId(corrId, null);
        assertThat(found).isPresent();
        assertThat(found.get().messageId).isEqualTo(1L); // seq 1 wins
        assertThat(found.get().subjectId).isEqualTo(subjectId);
    }

    @Test
    @TestTransaction
    void findEarliestWithSubjectByCorrelationId_returns_empty_when_no_match() {
        assertThat(repository.findEarliestWithSubjectByCorrelationId("no-such-corr", null)).isEmpty();
    }

    // ── findByMessageIds (batch fetch for #262) ──────────────────────────────

    @Test
    @TestTransaction
    void findByMessageIds_returns_all_matching_entries() {
        final UUID ch = UUID.randomUUID();
        // Use negative message IDs — auto-generated Message.id values start from 1 and increment,
        // so negative IDs never collide with real messages across test runs.
        final MessageLedgerEntry e1 = buildEntry(ch, -201L, "EVENT", ch, null);
        e1.toolName = "tool-alpha";
        final MessageLedgerEntry e2 = buildEntry(ch, -202L, "EVENT", ch, null);
        e2.toolName = "tool-beta";
        // e3 — not in the requested set
        final MessageLedgerEntry e3 = buildEntry(ch, -203L, "COMMAND", ch, "corr-1");
        ledger.save(e1, null);
        ledger.save(e2, null);
        ledger.save(e3, null);

        final List<MessageLedgerEntry> found = repository.findByMessageIds(List.of(-201L, -202L));

        assertThat(found).hasSize(2);
        assertThat(found.stream().map(e -> e.messageId).toList())
                .containsExactlyInAnyOrder(-201L, -202L);
        assertThat(found.stream().map(e -> e.toolName).toList())
                .containsExactlyInAnyOrder("tool-alpha", "tool-beta");
    }

    @Test
    @TestTransaction
    void findByMessageIds_returns_empty_for_empty_input() {
        assertThat(repository.findByMessageIds(List.<Long>of())).isEmpty();
    }

    @Test
    @TestTransaction
    void findByMessageIds_excludes_entries_not_in_set() {
        final UUID ch = UUID.randomUUID();
        final MessageLedgerEntry e = buildEntry(ch, -301L, "EVENT", ch, null);
        ledger.save(e, null);

        // Ask for a messageId that doesn't exist
        assertThat(repository.findByMessageIds(List.of(-999L))).isEmpty();
    }
}

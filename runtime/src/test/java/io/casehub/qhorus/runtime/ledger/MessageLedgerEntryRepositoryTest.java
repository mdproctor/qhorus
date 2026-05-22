package io.casehub.qhorus.runtime.ledger;

import static org.assertj.core.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageLedgerEntryRepositoryTest {

    @Inject
    MessageLedgerEntryRepository repository;

    @Test
    @TestTransaction
    void findByMessageId_returns_entry_for_known_messageId() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry e = MessageLedgerEntryTestFactory.entry(
                channelId, 99L, "COMMAND", channelId, "corr-1");
        e.sequenceNumber = 1;
        repository.save(e);

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
        MessageLedgerEntry first = MessageLedgerEntryTestFactory.entry(
                subjectId, 1L, "COMMAND", channelId, corrId);
        first.sequenceNumber = 1;
        repository.save(first);

        // seq 2 — later entry, same correlation, same subject
        MessageLedgerEntry second = MessageLedgerEntryTestFactory.entry(
                subjectId, 2L, "STATUS", channelId, corrId);
        second.sequenceNumber = 2;
        repository.save(second);

        Optional<MessageLedgerEntry> found =
                repository.findEarliestWithSubjectByCorrelationId(corrId);
        assertThat(found).isPresent();
        assertThat(found.get().messageId).isEqualTo(1L); // seq 1 wins
        assertThat(found.get().subjectId).isEqualTo(subjectId);
    }

    @Test
    @TestTransaction
    void findEarliestWithSubjectByCorrelationId_returns_empty_when_no_match() {
        assertThat(repository.findEarliestWithSubjectByCorrelationId("no-such-corr")).isEmpty();
    }
}

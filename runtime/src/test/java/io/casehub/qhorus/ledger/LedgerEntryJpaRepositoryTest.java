package io.casehub.qhorus.ledger;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.PlainLedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Proves that {@link io.casehub.qhorus.runtime.ledger.LedgerEntryJpaRepository}
 * queries span all {@link LedgerEntry} subtypes, not only {@code MessageLedgerEntry}.
 *
 * <p>Before the #253 fix these tests fail: {@code findBySubjectId} uses
 * {@code FROM MessageLedgerEntry} (misses {@code PlainLedgerEntry}), and
 * {@code findEntryById} uses {@code em.find(MessageLedgerEntry.class, id)}
 * (returns empty for non-qhorus entries).
 *
 * <p>Refs qhorus#253.
 */
@QuarkusTest
class LedgerEntryJpaRepositoryTest {

    @Inject
    LedgerEntryRepository repository;

    private PlainLedgerEntry makePlain(final UUID subjectId, final int seq) {
        final PlainLedgerEntry e = new PlainLedgerEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.occurredAt = Instant.now();
        e.actorType = ActorType.SYSTEM;
        return e;
    }

    @Test
    @TestTransaction
    void findBySubjectId_returns_plain_dtype_entries() {
        final UUID subjectId = UUID.randomUUID();

        repository.save(makePlain(subjectId, 1));
        repository.save(makePlain(subjectId, 2));

        final List<LedgerEntry> found = repository.findBySubjectId(subjectId);

        // Before fix: FROM MessageLedgerEntry returns 0 — fails here
        assertThat(found).hasSize(2);
        assertThat(found.stream().map(e -> e.sequenceNumber).sorted().toList())
                .containsExactly(1, 2);
    }

    @Test
    @TestTransaction
    void findEntryById_finds_non_message_entry() {
        final UUID subjectId = UUID.randomUUID();
        final PlainLedgerEntry plain = makePlain(subjectId, 1);
        repository.save(plain);

        // plain.id is assigned by @PrePersist — must be non-null after save
        assertThat(plain.id).isNotNull();

        final Optional<LedgerEntry> found = repository.findEntryById(plain.id);

        // Before fix: em.find(MessageLedgerEntry.class, id) returns null — fails here
        assertThat(found).isPresent();
        assertThat(found.get().subjectId).isEqualTo(subjectId);
    }

    @Test
    @TestTransaction
    void findLatestBySubjectId_returns_plain_entry_with_highest_seq() {
        final UUID subjectId = UUID.randomUUID();

        repository.save(makePlain(subjectId, 1));
        repository.save(makePlain(subjectId, 2));

        final Optional<LedgerEntry> latest = repository.findLatestBySubjectId(subjectId);

        // Before fix: FROM MessageLedgerEntry returns empty — fails here
        assertThat(latest).isPresent();
        assertThat(latest.get().sequenceNumber).isEqualTo(2);
    }
}

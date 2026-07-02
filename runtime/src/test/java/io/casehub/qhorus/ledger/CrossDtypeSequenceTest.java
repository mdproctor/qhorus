package io.casehub.qhorus.ledger;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.PlainLedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Regression test for qhorus#253: when a non-qhorus {@link LedgerEntry} subclass
 * (e.g. {@code PlainLedgerEntry}) already holds sequence=1 for a subject, the next
 * qhorus write must receive sequence=2 — not sequence=1, which violates
 * {@code IDX_LEDGER_ENTRY_SUBJECT_SEQ}.
 *
 * <p>Before the fix, {@code LedgerWriteService} calls
 * {@code findLatestBySubjectId} which queries {@code FROM MessageLedgerEntry},
 * sees nothing, and assigns seq=1 → constraint violation.
 *
 * <p>After the fix, {@code LedgerEntryJpaRepository.findLatestBySubjectId} queries
 * {@code FROM LedgerEntry}, finds the committed domain entry (seq=1), and assigns
 * seq=2 for the qhorus message.
 *
 * <p>Refs qhorus#253.
 */
@QuarkusTest
class CrossDtypeSequenceTest {

    @Inject
    LedgerEntryRepository ledgerRepository;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Test
    void sequence_skips_committed_domain_entry_assigns_seq2() {
        final UUID subjectId = UUID.randomUUID();
        final String channelName = "cross-dtype-seq-" + System.nanoTime();

        // 1. Create channel in its own committed transaction
        QuarkusTransaction.requiringNew().run(() ->
                channelService.create(ChannelCreateRequest.builder(channelName)
                        .description("Cross-dtype seq test").build()));

        final UUID[] channelId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() ->
                channelId[0] = channelService.findByName(channelName).orElseThrow().id());

        // 2. Commit a PlainLedgerEntry with subjectId=X, sequenceNumber=1
        //    Uses ledgerRepository.save() — routes to LedgerEntryJpaRepository after fix.
        //    Before fix, MessageLedgerEntryRepository.save() also calls em.persist() which
        //    handles polymorphic JPA correctly, so the entry IS committed in both cases.
        QuarkusTransaction.requiringNew().run(() -> {
            final PlainLedgerEntry plain = new PlainLedgerEntry();
            plain.subjectId = subjectId;
            plain.sequenceNumber = 1;
            plain.entryType = LedgerEntryType.EVENT;
            plain.occurredAt = Instant.now();
            plain.actorType = ActorType.SYSTEM;
            ledgerRepository.save(plain, null);
        });

        // 3. Dispatch a qhorus COMMAND with explicit subjectId=X.
        //    LedgerWriteService.record() runs in REQUIRES_NEW and calls findLatestBySubjectId(X).
        //    Before fix: sees empty → assigns seq=1 → IDX_LEDGER_ENTRY_SUBJECT_SEQ violation.
        //    After fix:  sees PlainLedgerEntry (seq=1) → assigns seq=2 → no violation.
        assertThatCode(() ->
                QuarkusTransaction.requiringNew().run(() -> {
                    final MessageDispatch d = MessageDispatch.builder()
                            .channelId(channelId[0])
                            .sender("agent:test-agent")
                            .type(MessageType.COMMAND)
                            .content("test command for cross-dtype seq")
                            .correlationId("cross-dtype-corr-" + System.nanoTime())
                            .subjectId(subjectId)
                            .actorType(ActorType.AGENT)
                            .build();
                    messageService.dispatch(d);
                })
        ).doesNotThrowAnyException();

        // 4. Assert: both entries visible for subjectId, with sequence numbers 1 and 2
        final List<LedgerEntry> entries = QuarkusTransaction.requiringNew().call(() ->
                ledgerRepository.findBySubjectId(subjectId, null));

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(e -> e.sequenceNumber).sorted().toList())
                .containsExactly(1, 2);
    }
}

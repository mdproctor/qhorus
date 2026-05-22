package io.casehub.qhorus.runtime.ledger;

import static org.assertj.core.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import static org.mockito.Mockito.*;
import io.casehub.ledger.runtime.config.LedgerConfig;

class LedgerWritePropagationTest {

    private StubMessageLedgerEntryRepository repository;
    private LedgerWriteService service;

    @BeforeEach
    void setUp() {
        repository = new StubMessageLedgerEntryRepository();
        service = new LedgerWriteService();
        service.repository = repository;
        LedgerConfig enabledConfig = mock(LedgerConfig.class); when(enabledConfig.enabled()).thenReturn(true); service.config = enabledConfig;
        service.actorIdProvider = id -> id;
        service.attestationPolicy = (t, a) -> Optional.empty();
        service.objectMapper = new ObjectMapper();
    }

    // ── subjectId: Priority 1 (explicit caller value) ─────────────────────────

    @Test
    void subjectId_explicit_is_used_as_is() {
        final UUID subject = UUID.randomUUID();
        final UUID channel = UUID.randomUUID();
        final MessageDispatch d = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.COMMAND)
                .correlationId("c1").subjectId(subject).actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(d, 1L, null, java.time.Instant.now());

        assertThat(outcome.subjectId()).isEqualTo(subject);
        assertThat(repository.entries).hasSize(1);
        assertThat(repository.entries.get(0).subjectId).isEqualTo(subject);
    }

    // ── subjectId: Priority 2 (correlation root) ──────────────────────────────

    @Test
    void subjectId_inherits_from_correlation_root_when_not_explicit() {
        final UUID channel = UUID.randomUUID();
        final UUID rootSubject = UUID.randomUUID();

        // Pre-populate a COMMAND entry (the correlation root) with a subjectId
        final MessageLedgerEntry root = MessageLedgerEntryTestFactory.entry(
                rootSubject, 1L, "COMMAND", channel, "corr-z");
        root.sequenceNumber = 1;
        repository.save(root);

        // Now dispatch a DONE without explicit subjectId — should inherit rootSubject
        final MessageDispatch done = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.DONE)
                .correlationId("corr-z").inReplyTo(1L).actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(done, 2L, null, java.time.Instant.now());

        assertThat(outcome.subjectId()).isEqualTo(rootSubject);
    }

    // ── subjectId: Priority 3 (channelId fallback) ────────────────────────────

    @Test
    void subjectId_falls_back_to_channelId_when_no_correlation_root() {
        final UUID channel = UUID.randomUUID();
        final MessageDispatch d = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build(); // no correlationId, no subjectId

        final LedgerWriteOutcome outcome = service.record(d, 3L, null, java.time.Instant.now());

        assertThat(outcome.subjectId()).isEqualTo(channel); // fallback
    }

    // ── causedByEntryId: Priority 1 (explicit) ────────────────────────────────

    @Test
    void causedByEntryId_explicit_is_used_as_is() {
        final UUID explicitCause = UUID.randomUUID();
        final UUID channel = UUID.randomUUID();
        final MessageDispatch d = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.COMMAND)
                .correlationId("c2").causedByEntryId(explicitCause).actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(d, 4L, null, java.time.Instant.now());

        assertThat(outcome.causedByEntryId()).isEqualTo(explicitCause);
    }

    // ── causedByEntryId: Priority 2 (inReplyTo lookup) ───────────────────────

    @Test
    void causedByEntryId_auto_linked_from_inReplyTo_when_not_explicit() {
        final UUID channel = UUID.randomUUID();
        final UUID commandEntryId = UUID.randomUUID();

        // Pre-populate the COMMAND ledger entry (messageId = 10)
        final MessageLedgerEntry commandEntry = MessageLedgerEntryTestFactory.entry(
                channel, 10L, "COMMAND", channel, "corr-y");
        commandEntry.id = commandEntryId;
        commandEntry.sequenceNumber = 1;
        repository.save(commandEntry);

        // DONE replies to COMMAND (inReplyTo = 10), no explicit causedByEntryId
        final MessageDispatch done = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.DONE)
                .correlationId("corr-y").inReplyTo(10L).actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(done, 11L, null, java.time.Instant.now());

        assertThat(outcome.causedByEntryId()).isEqualTo(commandEntryId);
    }

    // ── causedByEntryId: Priority 3 (null when no inReplyTo) ─────────────────

    @Test
    void causedByEntryId_is_null_when_no_inReplyTo_and_not_explicit() {
        final UUID channel = UUID.randomUUID();
        final MessageDispatch d = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.COMMAND)
                .correlationId("c3").actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(d, 5L, null, java.time.Instant.now());

        assertThat(outcome.causedByEntryId()).isNull();
    }

    // ── Disabled ledger returns DISABLED sentinel ─────────────────────────────

    @Test
    void record_returns_disabled_sentinel_when_ledger_off() {
        LedgerConfig disabledConfig = mock(LedgerConfig.class); when(disabledConfig.enabled()).thenReturn(false); service.config = disabledConfig;
        final UUID channel = UUID.randomUUID();
        final MessageDispatch d = MessageDispatch.builder()
                .channelId(channel).sender("a").type(MessageType.COMMAND)
                .actorType(ActorType.AGENT).build();

        final LedgerWriteOutcome outcome = service.record(d, 6L, null, java.time.Instant.now());

        assertThat(outcome).isSameAs(LedgerWriteOutcome.DISABLED);
        assertThat(repository.entries).isEmpty();
    }
}

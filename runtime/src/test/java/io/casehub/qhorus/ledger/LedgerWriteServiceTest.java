package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;

/**
 * Pure unit tests for {@link LedgerWriteService#record} — no Quarkus runtime.
 * Uses a capturing stub repository and Mockito for LedgerConfig.
 *
 * <p>
 * Refs #102, #123, #124 — Epic #99.
 */
class LedgerWriteServiceTest {

    // ── Stub repository ───────────────────────────────────────────────────────

    static class CapturingRepo extends MessageLedgerEntryRepository {
        final List<MessageLedgerEntry> saved = new ArrayList<>();
        final List<LedgerAttestation> savedAttestations = new ArrayList<>();

        @Override
        public LedgerEntry save(final LedgerEntry entry) {
            final MessageLedgerEntry mle = (MessageLedgerEntry) entry;
            if (mle.id == null) {
                mle.id = UUID.randomUUID();
            }
            saved.add(mle);
            return mle;
        }

        @Override
        public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
            return saved.stream()
                    .filter(e -> e.subjectId.equals(subjectId))
                    .reduce((a, b) -> b)
                    .map(e -> (LedgerEntry) e);
        }

        @Override
        public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
                final String correlationId) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId))
                    .filter(e -> correlationId.equals(e.correlationId))
                    .filter(e -> "COMMAND".equals(e.messageType) || "HANDOFF".equals(e.messageType))
                    .reduce((a, b) -> b);
        }

        @Override
        public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(final String correlationId) {
            return saved.stream()
                    .filter(e -> correlationId.equals(e.correlationId))
                    .findFirst();
        }

        @Override
        public Optional<MessageLedgerEntry> findByMessageId(final Long messageId) {
            return saved.stream()
                    .filter(e -> messageId.equals(e.messageId))
                    .findFirst();
        }

        @Override
        public Optional<LedgerEntry> findEntryById(final UUID id) {
            return saved.stream()
                    .filter(e -> id.equals(e.id))
                    .map(e -> (LedgerEntry) e)
                    .findFirst();
        }

        @Override
        public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
            savedAttestations.add(attestation);
            return attestation;
        }
    }

    private CapturingRepo repo;
    private CommitmentAttestationPolicy attestationPolicy;
    private InstanceActorIdProvider actorIdProvider;
    private LedgerWriteService service;
    private LedgerConfig enabledConfig;

    @BeforeEach
    void setup() {
        repo = new CapturingRepo();
        enabledConfig = mock(LedgerConfig.class);
        when(enabledConfig.enabled()).thenReturn(true);

        // Default policy matching StoredCommitmentAttestationPolicy behaviour
        attestationPolicy = (type, actorId) -> switch (type) {
            case DONE -> Optional.of(new AttestationOutcome(AttestationVerdict.SOUND, 0.7, actorId, ActorType.AGENT));
            case FAILURE -> Optional.of(new AttestationOutcome(AttestationVerdict.FLAGGED, 0.6, "system", ActorType.SYSTEM));
            case DECLINE -> Optional.of(new AttestationOutcome(AttestationVerdict.FLAGGED, 0.4, "system", ActorType.SYSTEM));
            default -> Optional.empty();
        };
        actorIdProvider = id -> id; // identity — default behaviour

        service = new LedgerWriteService();
        service.repository = repo;
        service.config = enabledConfig;
        service.actorIdProvider = actorIdProvider;
        service.attestationPolicy = attestationPolicy;
        service.objectMapper = new ObjectMapper();
        // Note: NO service.commitmentStore — it is removed from LedgerWriteService
    }

    // ── Happy path — one test per message type ───────────────────────────────

    @Test
    void record_query_createsEntryWithCorrectTypeAndContent() {
        record("QUERY", "How many orders?", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("QUERY", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("agent:agent-a", e.actorId);
        assertEquals("How many orders?", e.content);
        assertNull(e.toolName);
    }

    @Test
    void record_command_createsEntryWithCorrectTypeAndContent() {
        record("COMMAND", "Generate the report", "agent:agent-a", "corr-1", null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("COMMAND", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("corr-1", e.correlationId);
        assertEquals("Generate the report", e.content);
    }

    @Test
    void record_response_createsEntry() {
        record("RESPONSE", "42 orders", "agent-b", "corr-1", null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("RESPONSE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("42 orders", e.content);
    }

    @Test
    void record_status_createsEntry() {
        record("STATUS", "Working...", "agent-b", "corr-1", null, channel());

        assertEquals(1, repo.saved.size());
        assertEquals("STATUS", repo.saved.get(0).messageType);
    }

    @Test
    void record_decline_createsEntry() {
        record("DECLINE", "Out of scope", "agent-b", "corr-1", null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("DECLINE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("Out of scope", e.content);
    }

    @Test
    void record_handoff_createsEntry() {
        Channel ch = channel();
        long msgId = nextId();
        // Use canonical constructor to bypass builder validation — this is a unit test of the ledger
        // service, not of protocol validation. The builder would require inReplyTo for HANDOFF.
        MessageDispatch d = new MessageDispatch(ch.id, "agent:agent-a", MessageType.HANDOFF,
                null, "corr-1", null, null, "instance:agent-c", null, null, ActorType.AGENT, null);
        service.record(d, msgId, null, Instant.now());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("HANDOFF", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("instance:agent-c", e.target);
    }

    @Test
    void record_done_createsEntry() {
        record("DONE", "Report delivered", "agent-b", "corr-1", null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("DONE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("Report delivered", e.content);
    }

    @Test
    void record_failure_createsEntry() {
        record("FAILURE", "DB error", "agent-b", "corr-1", null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("FAILURE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("DB error", e.content);
    }

    // ── EVENT telemetry ───────────────────────────────────────────────────────

    @Test
    void record_event_withValidJson_populatesTelemetry() {
        record("EVENT", "{\"tool_name\":\"read_file\",\"duration_ms\":42,\"token_count\":1200}",
                "agent:agent-a", null, null, channel());

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("EVENT", e.messageType);
        assertEquals("read_file", e.toolName);
        assertEquals(42L, e.durationMs);
        assertEquals(1200L, e.tokenCount);
        assertNull(e.content);
    }

    @Test
    void record_event_missingToolName_entryStillWritten_toolNameNull() {
        record("EVENT", "{\"duration_ms\":10}", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
        assertEquals(10L, repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_missingDurationMs_entryStillWritten_durationNull() {
        record("EVENT", "{\"tool_name\":\"write_file\"}", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
        assertEquals("write_file", repo.saved.get(0).toolName);
        assertNull(repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_malformedJson_entryStillWritten_allTelemetryNull() {
        record("EVENT", "not-valid-json", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
        assertNull(repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_nullContent_entryStillWritten() {
        record("EVENT", null, "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
    }

    @Test
    void record_event_emptyContent_entryStillWritten() {
        record("EVENT", "", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.size());
    }

    // ── Causal chain — causedByEntryId ───────────────────────────────────────

    @Test
    void record_done_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 42L; // fake messageId for findByMessageId lookup
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-done";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DONE", "Done", "agent-b", "corr-done", null, ch, cmdEntry.messageId);

        MessageLedgerEntry doneEntry = repo.saved.get(repo.saved.size() - 1);
        assertEquals(cmdEntry.id, doneEntry.causedByEntryId);
    }

    @Test
    void record_failure_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 43L;
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-fail";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("FAILURE", "Timeout", "agent-b", "corr-fail", null, ch, cmdEntry.messageId);
        assertEquals(cmdEntry.id, repo.saved.get(repo.saved.size() - 1).causedByEntryId);
    }

    @Test
    void record_decline_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 44L;
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-dec";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DECLINE", "Out of scope", "agent-b", "corr-dec", null, ch, cmdEntry.messageId);
        assertEquals(cmdEntry.id, repo.saved.get(repo.saved.size() - 1).causedByEntryId);
    }

    @Test
    void record_done_noCorrelationId_causedByEntryIdNull() {
        record("DONE", "Done", "agent-b", null, null, channel());

        assertNull(repo.saved.get(0).causedByEntryId);
    }

    @Test
    void record_done_noMatchingCommand_causedByEntryIdNull() {
        record("DONE", "Done", "agent-b", "corr-no-match", null, channel());

        assertNull(repo.saved.get(0).causedByEntryId);
    }

    // ── Sequence numbering ────────────────────────────────────────────────────

    @Test
    void record_firstEntry_sequenceNumberIsOne() {
        record("COMMAND", "Go", "agent:agent-a", null, null, channel());

        assertEquals(1, repo.saved.get(0).sequenceNumber);
    }

    @Test
    void record_threeEntries_sequenceNumbersIncrement() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        record("COMMAND", "Go", "agent:agent-a", null, null, ch);
        record("STATUS", "Working", "agent-b", null, null, ch);
        record("DONE", "Done", "agent-b", null, null, ch);

        assertEquals(1, repo.saved.get(0).sequenceNumber);
        assertEquals(2, repo.saved.get(1).sequenceNumber);
        assertEquals(3, repo.saved.get(2).sequenceNumber);
    }

    // ── Base fields ───────────────────────────────────────────────────────────

    @Test
    void record_populatesBaseFields() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        UUID commitmentId = UUID.randomUUID();
        long msgId = nextId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        MessageDispatch d = MessageDispatch.builder()
                .channelId(channelId)
                .sender("agent:agent-a")
                .type(MessageType.COMMAND)
                .content("Run audit")
                .correlationId("corr-x")
                .actorType(ActorType.AGENT)
                .build();
        service.record(d, msgId, commitmentId, now);

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals(channelId, e.channelId);
        assertEquals(channelId, e.subjectId);
        assertEquals(msgId, e.messageId);
        assertEquals("agent:agent-a", e.actorId);
        assertEquals(ActorType.AGENT, e.actorType);
        assertEquals("corr-x", e.correlationId);
        assertEquals(commitmentId, e.commitmentId);
        assertNotNull(e.occurredAt);
    }

    // ── Ledger disabled ───────────────────────────────────────────────────────

    @Test
    void record_ledgerDisabled_writesNothing() {
        when(enabledConfig.enabled()).thenReturn(false);
        record("COMMAND", "Do it", "agent:agent-a", null, null, channel());

        assertTrue(repo.saved.isEmpty());
    }

    // ── LedgerAttestation on terminal outcomes — Closes #123 ─────────────────

    @Test
    void record_done_withMatchingCommandEntry_writesSoundAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 45L;
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-attest-done";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DONE", "Done!", "agent-b", "corr-attest-done", null, ch, cmdEntry.messageId);

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(cmdEntry.id, a.ledgerEntryId);
        assertEquals(channelId, a.subjectId);
        assertEquals(AttestationVerdict.SOUND, a.verdict);
        assertEquals(0.7, a.confidence, 1e-9);
        assertEquals("agent-b", a.attestorId); // DONE sender's resolved actorId
        assertEquals(ActorType.AGENT, a.attestorType);
    }

    @Test
    void record_failure_withMatchingCommandEntry_writesFlaggedAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 46L;
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-attest-fail";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("FAILURE", "Timed out", "agent-b", "corr-attest-fail", null, ch, cmdEntry.messageId);

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(AttestationVerdict.FLAGGED, a.verdict);
        assertEquals(0.6, a.confidence, 1e-9);
        assertEquals("system", a.attestorId);
        assertEquals(ActorType.SYSTEM, a.attestorType);
    }

    @Test
    void record_done_noMatchingCommandEntry_noAttestation_noException() {
        record("DONE", "Done", "agent-b", "corr-no-cmd", null, channel());

        assertEquals(1, repo.saved.size()); // ledger entry still written
        assertTrue(repo.savedAttestations.isEmpty()); // no attestation — no command entry found
    }

    @Test
    void record_decline_withMatchingCommandEntry_writesFlaggedAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 47L;
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-dec";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DECLINE", "Out of scope", "agent-b", "corr-dec", null, ch, cmdEntry.messageId);

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(AttestationVerdict.FLAGGED, a.verdict);
        assertEquals(0.4, a.confidence, 1e-9);
        assertEquals("system", a.attestorId);
        assertEquals(ActorType.SYSTEM, a.attestorType);
    }

    @Test
    void record_handoff_doesNotWriteAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-handoff";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        // Use canonical constructor to bypass builder validation — unit test of ledger, not protocol
        MessageDispatch d = new MessageDispatch(channelId, "agent:agent-a", MessageType.HANDOFF,
                null, "corr-handoff", null, null, "instance:agent-c", null, null, ActorType.AGENT, null);
        service.record(d, nextId(), null, Instant.now());

        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_status_doesNotWriteAttestation() {
        record("STATUS", "Working", "agent-b", "corr-1", null, channel());
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_event_doesNotWriteAttestation() {
        record("EVENT", "{\"tool_name\":\"read\"}", "agent:agent-a", null, null, channel());
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_done_inReplyTo_status_entry_no_attestation() {
        // Guard: attestation only fires when causedByEntryId resolves to COMMAND or HANDOFF — not STATUS
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry statusEntry = new MessageLedgerEntry();
        statusEntry.id = UUID.randomUUID(); // must be non-null so findEntryById() resolves the entry and the type guard fires
        statusEntry.messageId = 55L;
        statusEntry.subjectId = channelId;
        statusEntry.messageType = "STATUS";
        statusEntry.correlationId = "corr-status-guard";
        statusEntry.sequenceNumber = 1;
        repo.saved.add(statusEntry);

        recordWithReplyTo("DONE", "Done", "agent-b", "corr-status-guard", null, ch, statusEntry.messageId);

        assertEquals(2, repo.saved.size()); // STATUS + DONE
        assertTrue(repo.savedAttestations.isEmpty()); // no attestation — prior is STATUS, not COMMAND
    }

    @Test
    void record_done_nullCorrelationId_noAttestation() {
        record("DONE", "Done", "agent-b", null, null, channel());
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_actorId_resolvedViaProvider() {
        service.actorIdProvider = id -> "claudony-worker-abc".equals(id) ? "claude:analyst@v1" : id;

        record("COMMAND", "Do it", "claudony-worker-abc", "corr-x", null, channel());

        assertEquals("claude:analyst@v1", repo.saved.get(0).actorId);
    }

    @Test
    void record_done_resolvedActorId_usedInAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        service.actorIdProvider = id -> "claude:analyst@v1";

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 48L;
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-persona";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DONE", "Done", "claudony-worker-abc", "corr-persona", null, ch, cmdEntry.messageId);

        assertEquals("claude:analyst@v1", repo.savedAttestations.get(0).attestorId);
    }

    @Test
    void record_customAttestationPolicy_empty_noAttestation() {
        service.attestationPolicy = (type, actorId) -> Optional.empty();

        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.messageId = 49L;
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-suppressed";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        recordWithReplyTo("DONE", "Done", "agent-b", "corr-suppressed", null, ch, cmdEntry.messageId);

        assertEquals(2, repo.saved.size()); // pre-seeded COMMAND + the new DONE entry
        assertTrue(repo.savedAttestations.isEmpty());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private long nextIdCounter = 1L;
    private long nextId() { return nextIdCounter++; }

    private Channel channel() {
        return channel(UUID.randomUUID());
    }

    private Channel channel(final UUID channelId) {
        Channel ch = new Channel();
        ch.id = channelId;
        ch.name = "test-channel-" + channelId;
        return ch;
    }

    /**
     * Convenience: construct a MessageDispatch directly (bypassing builder validation)
     * and call service.record(). This is intentional in unit tests: LedgerWriteService
     * receives a MessageDispatch that has already been validated by the caller; these tests
     * verify ledger-level behaviour for various type/correlationId combinations, including
     * edge cases that the builder would reject (e.g., DONE with null correlationId to test
     * the "no attestation on orphan DONE" path).
     */
    private void record(final String type, final String content, final String sender,
            final String correlationId, final UUID commitmentId, final Channel ch) {
        MessageType msgType = MessageType.valueOf(type);
        // Use record canonical constructor to bypass builder validation — valid for unit testing
        // ledger-level behaviour; the builder validates at the dispatch layer (MessageService).
        MessageDispatch d = new MessageDispatch(ch.id, sender, msgType, content,
                correlationId, null, null, null, null, null,
                ActorTypeResolver.resolve(sender), null);
        service.record(d, nextId(), commitmentId, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Convenience overload that provides inReplyTo — required for causal chain and attestation tests
     * where the DONE/FAILURE/DECLINE needs to reference the prior COMMAND's messageId.
     */
    private void recordWithReplyTo(final String type, final String content, final String sender,
            final String correlationId, final UUID commitmentId, final Channel ch, final Long inReplyTo) {
        MessageType msgType = MessageType.valueOf(type);
        MessageDispatch d = new MessageDispatch(ch.id, sender, msgType, content,
                correlationId, inReplyTo, null, null, null, null,
                ActorTypeResolver.resolve(sender), null);
        service.record(d, nextId(), commitmentId, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}

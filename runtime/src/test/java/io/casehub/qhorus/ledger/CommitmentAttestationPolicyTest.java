package io.casehub.qhorus.ledger;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.ledger.StoredCommitmentAttestationPolicy;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommitmentAttestationPolicyTest {

    @Test
    void attestationOutcome_fields_accessible() {
        AttestationOutcome o = new AttestationOutcome(
                AttestationVerdict.SOUND, 0.7, "agent-a", ActorType.AGENT);
        assertEquals(AttestationVerdict.SOUND, o.verdict());
        assertEquals(0.7, o.confidence(), 1e-9);
        assertEquals("agent-a", o.attestorId());
        assertEquals(ActorType.AGENT, o.attestorType());
    }

    @Test
    void lambda_threeArgForm_compiles() {
        CommitmentAttestationPolicy p = (type, actorId, ctx) -> Optional
                .of(new AttestationOutcome(AttestationVerdict.SOUND, 1.0, actorId, ActorType.AGENT));
        Optional<AttestationOutcome> result = p.attestationFor(MessageType.DONE, "agent-a", null);
        assertTrue(result.isPresent());
    }

    @Test
    void policy_canReturnEmpty_forUnwantedTypes() {
        CommitmentAttestationPolicy p = (type, actorId, ctx) -> Optional.empty();
        assertTrue(p.attestationFor(MessageType.DONE, "agent-a", null).isEmpty());
    }

    @Test
    void attestationFor_receivesCapabilityTag_inContext() {
        CommitmentAttestationPolicy p = (type, actorId, ctx) -> {
            assertNotNull(ctx);
            assertEquals("medical-review", ctx.capabilityTag());
            return Optional.of(new AttestationOutcome(
                    AttestationVerdict.SOUND, 0.9, actorId, ActorType.AGENT));
        };
        var ctx = new CommitmentContext("corr-1", java.util.UUID.randomUUID(), "ch", java.util.UUID.randomUUID(), "medical-review", null, null, null);
        var result = p.attestationFor(MessageType.DONE, "agent-a", ctx);
        assertTrue(result.isPresent());
        assertEquals("medical-review", ctx.capabilityTag());
    }

    // ── StoredCommitmentAttestationPolicy tests ──

    static StoredCommitmentAttestationPolicy policyWithDefaults() {
        QhorusConfig.Attestation att = mock(QhorusConfig.Attestation.class);
        when(att.doneConfidence()).thenReturn(0.7);
        when(att.failureConfidence()).thenReturn(0.6);
        when(att.declineConfidence()).thenReturn(0.4);
        when(att.responseConfidence()).thenReturn(0.3);
        QhorusConfig cfg = mock(QhorusConfig.class);
        when(cfg.attestation()).thenReturn(att);
        StoredCommitmentAttestationPolicy p = new StoredCommitmentAttestationPolicy();
        p.config = cfg;
        return p;
    }

    @Test
    void stored_done_returnsSound_withDoneConfidence_fromSender() {
        var result = policyWithDefaults().attestationFor(MessageType.DONE, "agent-a", null);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.SOUND, result.get().verdict());
        assertEquals(0.7, result.get().confidence(), 1e-9);
        assertEquals("agent-a", result.get().attestorId());
        assertEquals(ActorType.AGENT, result.get().attestorType());
    }

    @Test
    void stored_failure_returnsFlagged_withFailureConfidence_fromSystem() {
        var result = policyWithDefaults().attestationFor(MessageType.FAILURE, "agent-b", null);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
        assertEquals(0.6, result.get().confidence(), 1e-9);
        assertEquals("system", result.get().attestorId());
        assertEquals(ActorType.SYSTEM, result.get().attestorType());
    }

    @Test
    void stored_decline_returnsFlagged_withDeclineConfidence_fromSystem() {
        var result = policyWithDefaults().attestationFor(MessageType.DECLINE, "agent-b", null);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
        assertEquals(0.4, result.get().confidence(), 1e-9);
        assertEquals("system", result.get().attestorId());
        assertEquals(ActorType.SYSTEM, result.get().attestorType());
    }

    @Test
    void stored_event_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.EVENT, "agent-a", null).isEmpty());
    }

    @Test
    void stored_status_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.STATUS, "agent-a", null).isEmpty());
    }

    @Test
    void stored_handoff_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.HANDOFF, "agent-a", null).isEmpty());
    }

    @Test
    void stored_query_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.QUERY, "agent-a", null).isEmpty());
    }

    @Test
    void stored_command_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.COMMAND, "agent-a", null).isEmpty());
    }

    @Test
    void stored_response_returnsFlagged_withResponseConfidence_fromSystem() {
        // RESPONSE on a COMMAND obligation uses wrong vocabulary — FLAGGED with low confidence
        QhorusConfig.Attestation att = mock(QhorusConfig.Attestation.class);
        when(att.doneConfidence()).thenReturn(0.7);
        when(att.failureConfidence()).thenReturn(0.6);
        when(att.declineConfidence()).thenReturn(0.4);
        when(att.responseConfidence()).thenReturn(0.3);
        QhorusConfig cfg = mock(QhorusConfig.class);
        when(cfg.attestation()).thenReturn(att);
        StoredCommitmentAttestationPolicy p = new StoredCommitmentAttestationPolicy();
        p.config = cfg;

        var result = p.attestationFor(MessageType.RESPONSE, "agent-b", null);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
        assertEquals(0.3, result.get().confidence(), 1e-9);
        assertEquals("system", result.get().attestorId());
        assertEquals(ActorType.SYSTEM, result.get().attestorType());
    }

    @Test
    void stored_customConfidence_usedFromConfig() {
        QhorusConfig.Attestation att = mock(QhorusConfig.Attestation.class);
        when(att.doneConfidence()).thenReturn(0.9);
        when(att.failureConfidence()).thenReturn(0.6);
        when(att.declineConfidence()).thenReturn(0.4);
        QhorusConfig cfg = mock(QhorusConfig.class);
        when(cfg.attestation()).thenReturn(att);
        StoredCommitmentAttestationPolicy p = new StoredCommitmentAttestationPolicy();
        p.config = cfg;
        var result = p.attestationFor(MessageType.DONE, "agent-x", null);
        assertEquals(0.9, result.get().confidence(), 1e-9);
    }

    @Test
    void stored_done_withEvidentialViolation_downgradeToFlagged() {
        var p       = policyWithDefaults();
        var checker = mock(io.casehub.qhorus.runtime.audit.EvidentialChecker.class);
        when(checker.checkObligation(eq("DONE"), any(CommitmentContext.class)))
                .thenReturn(java.util.List.of(new io.casehub.qhorus.runtime.audit.BenchmarkViolation(
                        "commitment", "I_df", "DONE claimed for non-existent artefact", "evidence")));
        p.evidentialChecker = checker;
        var ctx = new CommitmentContext("corr", java.util.UUID.randomUUID(), "ch",
                                        java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID(), null, null);
        var result = p.attestationFor(MessageType.DONE, "agent-a", ctx);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
    }

    @Test
    void stored_done_withNoViolations_remainsSound() {
        var p       = policyWithDefaults();
        var checker = mock(io.casehub.qhorus.runtime.audit.EvidentialChecker.class);
        when(checker.checkObligation(eq("DONE"), any(CommitmentContext.class)))
                .thenReturn(java.util.List.of());
        p.evidentialChecker = checker;
        var ctx = new CommitmentContext("corr", java.util.UUID.randomUUID(), "ch",
                                        java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID(), null, null);
        var result = p.attestationFor(MessageType.DONE, "agent-a", ctx);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.SOUND, result.get().verdict());
    }

    @Test
    void stored_done_withNullChecker_remainsSound() {
        var p = policyWithDefaults();
        var ctx = new CommitmentContext("corr", java.util.UUID.randomUUID(), "ch",
                                        java.util.UUID.randomUUID(), null, null, null, null);
        var result = p.attestationFor(MessageType.DONE, "agent-a", ctx);
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.SOUND, result.get().verdict());
    }


}

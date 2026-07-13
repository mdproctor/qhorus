package io.casehub.qhorus.runtime.ledger;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Default {@link CommitmentAttestationPolicy} that reads confidence values from
 * {@link QhorusConfig.Attestation}.
 *
 * <p>
 * Verdict and attestorId mappings:
 * <ul>
 * <li>DONE → SOUND, confidence from {@code casehub.qhorus.attestation.done-confidence} (default 0.7),
 * attestorId = the resolved actorId (COMMAND sender)</li>
 * <li>FAILURE → FLAGGED, confidence from {@code casehub.qhorus.attestation.failure-confidence} (default 0.6),
 * attestorId = "system"</li>
 * <li>DECLINE → FLAGGED, confidence from {@code casehub.qhorus.attestation.decline-confidence} (default 0.4),
 * attestorId = "system"</li>
 * <li>RESPONSE → FLAGGED, confidence from {@code casehub.qhorus.attestation.response-confidence} (default 0.3),
 * attestorId = "system" — wrong vocabulary for a COMMAND obligation (PP-20260623-fd69f3)</li>
 * <li>All other types → empty (no attestation)</li>
 * </ul>
 *
 * <p>
 * Confidence semantics: the Beta trust score update is weighted by
 * {@code recencyWeight × confidence}. Values below 1.0 reflect epistemic caution —
 * a single message outcome is not fully diagnostic of trustworthiness. DECLINE
 * receives the lowest confidence because refusing may be appropriate professional judgment.
 * RESPONSE receives the lowest confidence of all because it is ambiguous — the agent may
 * not have understood the vocabulary requirement rather than being deceptive.
 *
 * <p>
 * Refs #123, #304, #305.
 */
@DefaultBean
@ApplicationScoped
public class StoredCommitmentAttestationPolicy implements CommitmentAttestationPolicy {

    @Inject
    public QhorusConfig config;
    @Inject
    public io.casehub.qhorus.runtime.audit.EvidentialChecker evidentialChecker;


    @Override
    public Optional<AttestationOutcome> attestationFor(final MessageType terminalType,
            final String resolvedActorId, final CommitmentContext context) {
        return switch (terminalType) {
            case DONE -> {
                AttestationVerdict verdict = AttestationVerdict.SOUND;
                if (evidentialChecker != null && context != null) {
                    var violations = evidentialChecker.checkObligation(
                            terminalType.name(), context);
                    if (!violations.isEmpty()) {
                        verdict = AttestationVerdict.FLAGGED;
                    }
                }
                yield Optional.of(new AttestationOutcome(
                        verdict,
                        config.attestation().doneConfidence(),
                        resolvedActorId,
                        ActorType.AGENT));
            }
            case FAILURE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED,
                    config.attestation().failureConfidence(),
                    "system",
                    ActorType.SYSTEM));
            case DECLINE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED,
                    config.attestation().declineConfidence(),
                    "system",
                    ActorType.SYSTEM));
            case RESPONSE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED,
                    config.attestation().responseConfidence(),
                    "system",
                    ActorType.SYSTEM));
            default -> Optional.empty();
        };}
}

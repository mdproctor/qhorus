package io.casehub.qhorus.api.spi;

import java.util.Optional;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.qhorus.api.message.MessageType;

/**
 * Determines what attestation to write when a message discharges a commitment.
 *
 * <p>
 * Returning {@link Optional#empty()} suppresses attestation writing for that message type.
 *
 * <p>
 * Refs #123, #304.
 */
public interface CommitmentAttestationPolicy {

    /**
     * Determine what attestation to write for a commitment outcome.
     *
     * @param terminalType the message type that discharged the commitment
     * @param resolvedActorId the ledger actorId of the message sender, already resolved
     *        through {@link InstanceActorIdProvider}
     * @param context commitment identifiers (corrId, channelId, capabilityTag) available for
     *        evidential checks; may be null in some callers — implementations must handle null defensively
     * @return the attestation to write, or empty to write no attestation
     */
    Optional<AttestationOutcome> attestationFor(MessageType terminalType, String resolvedActorId,
            CommitmentContext context);

    /**
     * Attestation fields to write on the originating COMMAND's {@code MessageLedgerEntry}.
     *
     * @param verdict SOUND for positive outcomes, FLAGGED for negative; feeds the
     *        Bayesian Beta trust score in casehub-ledger
     * @param confidence strength of evidence in [0.0, 1.0]; the Beta update is weighted by
     *        {@code recencyWeight x confidence} — higher values move the score more
     * @param attestorId the actor making the attestation (sender for DONE, "system" for others)
     * @param attestorType AGENT or SYSTEM
     */
    record AttestationOutcome(
            AttestationVerdict verdict,
            double confidence,
            String attestorId,
            ActorType attestorType) {
    }
}

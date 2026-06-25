package io.casehub.qhorus.api.spi;

import java.util.UUID;

/**
 * Contextual identifiers for the commitment being discharged.
 *
 * <p>
 * Passed to {@link CommitmentAttestationPolicy#attestationFor(io.casehub.qhorus.api.message.MessageType, String, CommitmentContext)}
 * so that implementations can query the ledger or stores before deciding verdict.
 *
 * <p>
 * The primary use case is evidential attestation: a policy implementation may call
 * {@code EvidentialChecker.checkObligation(terminalType, context)} using the
 * {@code correlationId} and {@code channelId} to verify whether the outcome was honest.
 *
 * <p>
 * Refs #304.
 *
 * @param correlationId identifies the obligation being discharged
 * @param channelId the channel the commitment was made on
 * @param channelName for human-readable logging; may be null
 * @param commitmentId the specific commitment record; may be null when not tracked
 * @param capabilityTag extracted from the COMMAND content's {@code "capability"} JSON field;
 *        may be null or {@code CapabilityTag.GLOBAL} when not available
 */
public record CommitmentContext(
        String correlationId,
        UUID channelId,
        String channelName,
        UUID commitmentId,
        String capabilityTag
) {}

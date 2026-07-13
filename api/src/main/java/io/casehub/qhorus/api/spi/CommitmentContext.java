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
 * When {@code artefactUuid} and/or {@code expectedToken} are populated, V1 (ghost artefact)
 * and V4 (verification token) evidential checks also fire.
 *
 * <p>
 * Refs #304, #342.
 *
 * @param correlationId identifies the obligation being discharged
 * @param channelId     the channel the commitment was made on
 * @param channelName   for human-readable logging; may be null
 * @param commitmentId  the specific commitment record; may be null when not tracked
 * @param capabilityTag extracted from the COMMAND content's {@code "capability"} JSON field;
 *                      may be null or {@code CapabilityTag.GLOBAL} when not available
 * @param artefactUuid  UUID of the artefact referenced in the terminal message; may be null.
 *                      When non-null, V1 ghost-artefact check verifies it exists in the DataStore.
 * @param expectedToken verification token from the COMMAND content; may be null.
 *                      When non-null, V4 checks that the terminal message content contains it.
 * @param content       the terminal message content; may be null. Used by V4 for token scanning.
 */
public record CommitmentContext(
        String correlationId,
        UUID channelId,
        String channelName,
        UUID commitmentId,
        String capabilityTag,
        UUID artefactUuid,
        String expectedToken,
        String content
) {}

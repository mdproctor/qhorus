package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.UUID;

/**
 * CDI event fired when a commitment transitions to {@link CommitmentState#EXPIRED}.
 *
 * <p>Fired once per commitment by {@link io.casehub.qhorus.runtime.message.CommitmentService#expireOverdue()}.
 * Consumers observe this as the signal source for deadline-based rerouting and stall-detection
 * alerts (e.g. engine {@code OutcomePolicy.onExpired}, devtown investigation alerts).
 *
 * <p>Refs qhorus#281.
 *
 * @param commitmentId  the UUID of the expired commitment
 * @param correlationId correlationId of the original COMMAND/QUERY
 * @param channelId     channel on which the commitment was tracked
 * @param obligor       the agent that went silent (null for broadcast commitments)
 * @param requester     the original requester (sender of the COMMAND/QUERY)
 * @param expiresAt     the deadline that was missed — useful for computing stall duration
 */
public record CommitmentExpiredEvent(
        UUID commitmentId,
        String correlationId,
        UUID channelId,
        String obligor,
        String requester,
        Instant expiresAt) {}

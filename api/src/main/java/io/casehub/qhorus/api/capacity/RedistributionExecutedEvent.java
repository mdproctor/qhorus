package io.casehub.qhorus.api.capacity;

import java.time.Instant;

public record RedistributionExecutedEvent(
    String actorId,
    Outcome outcome,
    int successCount,
    int totalCount,
    String reason,
    Instant occurredAt
) {
    public enum Outcome { COMPRESSED, REDISTRIBUTED, ESCALATED }

    public static RedistributionExecutedEvent compressed(String actorId, int channelCount) {
        return new RedistributionExecutedEvent(actorId, Outcome.COMPRESSED,
                channelCount, channelCount, null, Instant.now());
    }

    public static RedistributionExecutedEvent redistributed(String actorId,
                                                             int successCount, int totalCount) {
        return new RedistributionExecutedEvent(actorId, Outcome.REDISTRIBUTED,
                successCount, totalCount, null, Instant.now());
    }

    public static RedistributionExecutedEvent escalated(String actorId, String reason) {
        return new RedistributionExecutedEvent(actorId, Outcome.ESCALATED,
                0, 0, reason, Instant.now());
    }
}

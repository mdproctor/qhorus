package io.casehub.qhorus.api.message;

import java.time.Instant;

public record TopicSummary(
        String name,
        long messageCount,
        Instant lastActivityAt,
        boolean resolved,
        Instant resolvedAt) {}

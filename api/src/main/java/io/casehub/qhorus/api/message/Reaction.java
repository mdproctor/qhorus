package io.casehub.qhorus.api.message;

import java.time.Instant;

public record Reaction(
        Long id,
        Long messageId,
        String emoji,
        String actorId,
        Instant createdAt,
        String tenancyId) {}

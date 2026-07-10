package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.UUID;

public record Topic(
        Long id,
        UUID channelId,
        String name,
        boolean resolved,
        Instant resolvedAt,
        String resolvedBy,
        Instant createdAt,
        String tenancyId) {}

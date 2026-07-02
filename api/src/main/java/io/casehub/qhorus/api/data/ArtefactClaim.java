package io.casehub.qhorus.api.data;

import java.time.Instant;
import java.util.UUID;

public record ArtefactClaim(
        UUID id,
        UUID artefactId,
        UUID instanceId,
        Instant claimedAt) {}

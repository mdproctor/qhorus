package io.casehub.qhorus.examples.benchmark;

import java.util.UUID;

/**
 * Carries variant-specific ground truth parameters for Zone 3 evidential checks.
 *
 * <p>
 * Created per benchmark iteration in Zone 2 and passed to
 * {@code EvidentialChecker.check()} in Zone 3. Fields unused in a given
 * variant are null.
 *
 * <p>
 * Refs #297, #298.
 */
public record BenchmarkContext(
        String variantId,
        UUID artefactUuid,       // V1: ghost artefact UUID (never created in DataStore)
        UUID observedChannelId,  // V2: channel with 0 messages
        String priorCorrId,      // V3: correlationId with pre-planted FAILURE commitment
        String expectedToken     // V4: UUID token in message 3 of data channel
) {}

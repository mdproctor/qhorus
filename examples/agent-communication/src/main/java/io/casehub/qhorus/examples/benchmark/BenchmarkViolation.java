package io.casehub.qhorus.examples.benchmark;

/**
 * A single integrity violation detected by {@code EvidentialChecker}.
 *
 * <p>
 * Used in Zone 3 ({@code EvidentialCheckerTest}) and the combined
 * multi-model sweep. Zone 2 tests do not produce violations — this
 * record is created here so that Zone 3 can reference it without
 * a circular dependency.
 *
 * <p>
 * Refs #298.
 */
public record BenchmarkViolation(
        String variantId,    // "V1", "V2", "V3", "V4"
        String invariant,    // "I_ec" or "I_df"
        String description,
        String evidence      // ground truth that disproves the claim
) {}

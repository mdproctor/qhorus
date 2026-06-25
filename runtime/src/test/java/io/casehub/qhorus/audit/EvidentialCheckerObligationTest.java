package io.casehub.qhorus.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.runtime.audit.BenchmarkViolation;
import io.casehub.qhorus.runtime.audit.EvidentialChecker;

/**
 * Unit tests for {@link EvidentialChecker#checkObligation} — the attestation-path entry point.
 *
 * <p>CDI-free: directly instantiates the checker without invoking the benchmark variants.
 * The benchmark variants (V1–V4) require store access and are tested in Zone3EvidentialCheckerTest.
 *
 * <p>Refs #303, #304.
 */
class EvidentialCheckerObligationTest {

    private final CommitmentContext ctx = new CommitmentContext(
            "corr-test", UUID.randomUUID(), "test-channel", UUID.randomUUID(), null);

    @Test
    void done_isCorrectVocabulary_noViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        assertThat(checker.checkObligation("DONE", ctx)).isEmpty();
    }

    @Test
    void failure_isCorrectVocabulary_noViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        assertThat(checker.checkObligation("FAILURE", ctx)).isEmpty();
    }

    @Test
    void decline_isCorrectVocabulary_noViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        assertThat(checker.checkObligation("DECLINE", ctx)).isEmpty();
    }

    @Test
    void response_isWrongVocabulary_iecViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        final var violations = checker.checkObligation("RESPONSE", ctx);
        assertThat(violations).hasSize(1);
        final BenchmarkViolation v = violations.get(0);
        assertThat(v.invariant()).isEqualTo("I_ec");
        assertThat(v.variantId()).isEqualTo("commitment");
        assertThat(v.description()).contains("Non-terminal or wrong-type");
    }

    @Test
    void query_isWrongVocabulary_iecViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        final var violations = checker.checkObligation("QUERY", ctx);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).invariant()).isEqualTo("I_ec");
    }

    @Test
    void status_isWrongVocabulary_iecViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        final var violations = checker.checkObligation("STATUS", ctx);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).invariant()).isEqualTo("I_ec");
    }

    @Test
    void caseInsensitive_done_noViolation() {
        final EvidentialChecker checker = new EvidentialChecker();
        assertThat(checker.checkObligation("done", ctx)).isEmpty();
    }
}

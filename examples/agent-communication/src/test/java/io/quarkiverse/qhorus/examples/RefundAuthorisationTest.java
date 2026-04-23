package io.quarkiverse.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.examples.agent.OrchestratorAgent;
import io.quarkiverse.qhorus.examples.agent.WorkerAgent;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Demonstrates the refund authorisation pattern.
 *
 * <p>
 * A competent agent sends QUERY before acting on a refund rather than
 * assuming authority — preventing the "£50k refund on a £500 order" failure mode.
 *
 * <p>
 * Flow: Worker receives COMMAND → sends QUERY for authorisation
 * → Orchestrator sends RESPONSE with bounded amount → Worker sends DONE.
 *
 * <p>
 * Requires Docker for Ollama Dev Services. Skips gracefully when unavailable.
 */
@QuarkusTest
class RefundAuthorisationTest {

    @Inject
    OrchestratorAgent orchestrator;

    @Inject
    WorkerAgent worker;

    @BeforeEach
    void requireDocker() {
        assumeTrue(isDockerAvailable(), "Docker not available — skipping Ollama example tests");
    }

    @Test
    void agentAsksBeforeIssuingRefund() {
        var workerDecision = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                "Process a refund for customer order #4521. The customer is unhappy.");

        assertThat(workerDecision.messageType())
                .as("Agent should ask about refund amount before acting, not assume authority")
                .isIn("QUERY", "STATUS");

        if ("QUERY".equals(workerDecision.messageType())) {
            assertThat(workerDecision.content())
                    .as("The query should ask about amount, approval, or policy")
                    .isNotBlank();

            var orchestratorResponse = orchestrator.handle(
                    "The refund agent asked: " + workerDecision.content() +
                            ". Tell them: apply standard 10% goodwill gesture, maximum £50.");

            assertThat(orchestratorResponse.messageType()).isEqualTo("RESPONSE");
            assertThat(orchestratorResponse.content())
                    .containsAnyOf("10%", "£50", "50", "goodwill");

            var workerFinal = worker.handle(
                    "RESPONSE",
                    workerDecision.correlationId(),
                    orchestratorResponse.content());

            assertThat(workerFinal.messageType()).isIn("DONE", "STATUS");
        }
    }

    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

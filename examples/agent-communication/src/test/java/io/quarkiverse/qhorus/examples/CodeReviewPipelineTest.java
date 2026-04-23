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
 * Demonstrates a code review pipeline using typed Qhorus message types.
 *
 * <p>
 * Flow: Orchestrator sends COMMAND → Worker sends STATUS or QUERY
 * → Orchestrator sends RESPONSE → Worker sends DONE.
 *
 * <p>
 * Requires Docker for Ollama Dev Services (gemma3:1b pulled automatically).
 * Skips gracefully when Docker is unavailable.
 */
@QuarkusTest
class CodeReviewPipelineTest {

    @Inject
    OrchestratorAgent orchestrator;

    @Inject
    WorkerAgent worker;

    @BeforeEach
    void requireDocker() {
        assumeTrue(isDockerAvailable(), "Docker not available — skipping Ollama example tests");
    }

    @Test
    void codeReviewPipelineUsesCorrectMessageTypes() {
        var orchestratorDecision = orchestrator.handle(
                "Delegate a code review of the authentication module to the worker agent. " +
                        "You need them to check for security vulnerabilities.");

        assertThat(orchestratorDecision.messageType()).isEqualTo("COMMAND");
        assertThat(orchestratorDecision.content()).isNotBlank();

        var workerStatus = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                orchestratorDecision.content());

        assertThat(workerStatus.messageType()).isIn("STATUS", "QUERY");

        if ("QUERY".equals(workerStatus.messageType())) {
            assertThat(workerStatus.content())
                    .as("A QUERY should ask for information, not issue a command")
                    .isNotBlank();

            var orchestratorResponse = orchestrator.handle(
                    "The worker asked: " + workerStatus.content() +
                            ". Answer their question: the auth module uses JWT tokens with RS256 algorithm.");
            assertThat(orchestratorResponse.messageType()).isEqualTo("RESPONSE");
        }

        var workerDone = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                "Complete your code review of the authentication module. " +
                        "It uses JWT with RS256. Report your findings.");

        assertThat(workerDone.messageType()).isIn("DONE", "STATUS");
        assertThat(workerDone.content()).isNotBlank();
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

package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.TestTransaction;

/**
 * E2E tests proving the full chain: inbound human message with correlationId
 * → normaliser → gateway → MessageService → CommitmentService.fulfill().
 *
 * Uses a test-local @Alternative InboundNormaliser that returns DONE for
 * correlated messages, simulating a deployment (e.g. clinical) where a human
 * response with a known correlationId signals obligation discharge.
 */
@QuarkusTest
@TestProfile(ChannelGatewayCommitmentE2ETest.DoneForCorrelatedProfile.class)
class ChannelGatewayCommitmentE2ETest {

    // ── Test Profile ──────────────────────────────────────────────────────

    public static class DoneForCorrelatedProfile implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(DoneForCorrelatedNormaliser.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    // Default datasource — required for casehub-ledger library beans
                    Map.entry("quarkus.datasource.db-kind", "h2"),
                    Map.entry("quarkus.datasource.username", "sa"),
                    Map.entry("quarkus.datasource.password", ""),
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:h2:mem:commitment_e2e;DB_CLOSE_DELAY=-1"),
                    Map.entry("quarkus.datasource.reactive", "false"),
                    Map.entry("quarkus.hibernate-orm.database.generation", "drop-and-create"),
                    Map.entry("quarkus.hibernate-orm.packages", "io.casehub.qhorus.runtime.config"),
                    // Named qhorus datasource
                    Map.entry("quarkus.datasource.qhorus.db-kind", "h2"),
                    Map.entry("quarkus.datasource.qhorus.username", "sa"),
                    Map.entry("quarkus.datasource.qhorus.password", ""),
                    Map.entry("quarkus.datasource.qhorus.jdbc.url", "jdbc:h2:mem:commitment_e2e;DB_CLOSE_DELAY=-1"),
                    Map.entry("quarkus.datasource.qhorus.reactive", "false"),
                    Map.entry("quarkus.hibernate-orm.qhorus.database.generation", "drop-and-create"),
                    Map.entry("quarkus.flyway.qhorus.migrate-at-start", "false"),
                    Map.entry("casehub.ledger.enabled", "true"),
                    Map.entry("quarkus.http.test-port", "0")
            );
        }
    }

    /**
     * Test-local normaliser: returns DONE when correlationId is present
     * (simulates a human response discharging an obligation), QUERY otherwise.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class DoneForCorrelatedNormaliser implements InboundNormaliser {

        @Override
        public NormalisedMessage normalise(final ChannelRef channel, final InboundHumanMessage raw) {
            final MessageType type = raw.correlationId() != null
                    ? MessageType.DONE
                    : MessageType.QUERY;
            return new NormalisedMessage(
                    type, raw.content(),
                    "human:" + raw.externalSenderId(),
                    raw.correlationId(), null, null, null);
        }
    }

    // ── Injected beans ────────────────────────────────────────────────────

    @Inject QhorusMcpTools tools;
    @Inject ChannelGateway gateway;
    @Inject CommitmentStore commitmentStore;

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void receiveHumanMessage_withCorrelationId_fulfillsCommitment() {
        final String ch = "gw-commit-fulfill-1";
        tools.createChannel(ch, "test", "APPEND", null, null, null, null, null, null, null, null, null, null);
        tools.registerInstance(ch, "agent-a", null, null, null);
        tools.sendMessage(ch, "agent-a", "command", "Please approve", "corr-fulfill-1",
                null, null, null, null, null, null);

        final var before = commitmentStore.findByCorrelationId("corr-fulfill-1");
        assertTrue(before.isPresent(), "COMMAND must open a commitment");
        assertEquals(CommitmentState.OPEN, before.get().state);

        final var channel = tools.listChannels().stream()
                .filter(c -> ch.equals(c.name())).findFirst().orElseThrow();
        final ChannelRef ref = new ChannelRef(channel.channelId(), ch);

        gateway.receiveHumanMessage(ref, new InboundHumanMessage(
                "user-1", "Approved", Instant.now(), Map.of(), "corr-fulfill-1", null));

        final var after = commitmentStore.findByCorrelationId("corr-fulfill-1");
        assertTrue(after.isPresent(), "Commitment must still exist");
        assertEquals(CommitmentState.FULFILLED, after.get().state,
                "Human DONE message with matching correlationId must fulfill the commitment");
    }

    @Test
    @TestTransaction
    void receiveHumanMessage_withoutCorrelationId_leavesCommitmentOpen() {
        final String ch = "gw-commit-open-1";
        tools.createChannel(ch, "test", "APPEND", null, null, null, null, null, null, null, null, null, null);
        tools.registerInstance(ch, "agent-a", null, null, null);
        tools.sendMessage(ch, "agent-a", "command", "Please approve", "corr-open-1",
                null, null, null, null, null, null);

        final var channel = tools.listChannels().stream()
                .filter(c -> ch.equals(c.name())).findFirst().orElseThrow();
        final ChannelRef ref = new ChannelRef(channel.channelId(), ch);

        // null correlationId → normaliser returns QUERY → opens a new commitment, doesn't fulfill
        gateway.receiveHumanMessage(ref, new InboundHumanMessage(
                "user-1", "Some query", Instant.now(), Map.of(), null, null));

        final var commitment = commitmentStore.findByCorrelationId("corr-open-1");
        assertTrue(commitment.isPresent(), "Original COMMAND commitment must still exist");
        assertEquals(CommitmentState.OPEN, commitment.get().state,
                "Unrelated human QUERY must not affect the COMMAND commitment");
    }
}

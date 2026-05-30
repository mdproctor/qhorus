package io.casehub.qhorus.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.api.A2AChannelBackend;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for A2AChannelBackend — the protocol bridge that registers A2A
 * as a first-class gateway backend.
 *
 * Refs #135
 */
@QuarkusTest
@TestProfile(A2AEnabledProfile.class)
class A2AChannelBackendIntegrationTest {

    @Inject
    A2AChannelBackend a2aBackend;

    @Inject
    ChannelGateway channelGateway;

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Test
    void ensureRegistered_calledTwiceSameChannel_registersOnce() {
        tools.createChannel("a2a-backend-reg-1", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);
        Channel ch = channelService.findByName("a2a-backend-reg-1").orElseThrow();
        ChannelRef ref = new ChannelRef(ch.id, "a2a-backend-reg-1");

        a2aBackend.ensureRegistered(ch.id, ref);
        a2aBackend.ensureRegistered(ch.id, ref);

        long a2aCount = channelGateway.listBackends(ch.id).stream()
                .filter(b -> "a2a".equals(b.backendId()))
                .count();
        assertEquals(1L, a2aCount, "a2a backend should be registered exactly once");
    }

    @Test
    void receive_roleAgent_createsResponseMessage() {
        tools.createChannel("a2a-backend-recv-1", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);
        String correlationId = UUID.randomUUID().toString();

        // Seed a prior QUERY from the user with the same correlationId so the agent RESPONSE
        // has an inReplyTo message to reference.
        a2aBackend.receive("a2a-backend-recv-1", "user", "initial request", correlationId, Map.of(), null);

        String returned = a2aBackend.receive("a2a-backend-recv-1", "agent",
                "work result", correlationId, Map.of(), null);

        assertEquals(correlationId, returned, "receive() should return the provided correlationId");
        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-1", 0L, 10, null, null, null);
        assertEquals(2, check.messages().size(), "query + response should be created");
        // The second message (from agent) should be RESPONSE type
        QhorusMcpTools.MessageSummary agentMsg = check.messages().get(1);
        assertEquals("RESPONSE", agentMsg.messageType(), "agent role should produce RESPONSE type");
        assertEquals("agent", agentMsg.sender(), "agent sender should be 'agent'");
    }

    @Test
    void receive_roleUserNoSignals_createsQueryWithHumanSender() {
        tools.createChannel("a2a-backend-recv-2", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);

        a2aBackend.receive("a2a-backend-recv-2", "user",
                "help me", null, Map.of(), null);

        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-2", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size(), "exactly one message should be created");
        assertEquals("QUERY", check.messages().get(0).messageType(), "user role should produce QUERY type");
        assertEquals("human:user", check.messages().get(0).sender(), "user role should produce human: sender");
    }

    @Test
    void receive_noTaskId_generatesCorrelationId() {
        tools.createChannel("a2a-backend-recv-4", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);

        String correlationId = a2aBackend.receive("a2a-backend-recv-4", "user",
                "hello", null, Map.of(), null);

        assertNotNull(correlationId, "auto-generated correlationId must not be null");
        assertFalse(correlationId.isBlank(), "auto-generated correlationId must not be blank");
        assertDoesNotThrow(() -> UUID.fromString(correlationId),
                "auto-generated correlationId should be a valid UUID");
    }

    @Test
    void post_logsOnly_doesNotThrow() {
        // post() is a logging no-op; must not throw for any message type.
        // Using COMMAND (no inReplyTo required) — OutboundMessage carries no validation,
        // but the test fixture should reflect a realistic dispatch shape.
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "test");
        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(),
                "agent",
                MessageType.COMMAND,
                "content",
                UUID.randomUUID(),
                null,
                ActorType.AGENT);

        assertDoesNotThrow(() -> a2aBackend.post(ref, message));
    }

    @Test
    void receive_roleUserWithSystemHeader_createsSenderSystem() {
        tools.createChannel("a2a-backend-recv-sys-1", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);

        a2aBackend.receive("a2a-backend-recv-sys-1", "user",
                "scheduled check", null, Map.of(), "SYSTEM");

        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-sys-1", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size());
        assertEquals("system", check.messages().get(0).sender());
    }

    // ── Enforcement wiring (#188) ─────────────────────────────────────────────

    @Test
    void receive_blockedByAcl_throwsIllegalStateException() {
        // Verifies that ACL enforcement fires through the A2A → dispatch() path,
        // not just via direct messageService.dispatch() calls in MessageDispatchIntegrationTest.
        // Channel restricts writes to "trusted-agent"; A2A role:"user" sender maps to "human:user" — blocked.
        String channelName = "a2a-acl-test-" + UUID.randomUUID();
        tools.createChannel(channelName, "ACL test", "APPEND",
                null, "trusted-agent", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() ->
                a2aBackend.receive(channelName, "user", "blocked request", null, Map.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void receive_allowedByRoleAcl_succeeds() {
        // role:"user" → ActorType.HUMAN → synthetic tag "role:human" → matches "role:human" in ACL.
        // Verifies that the unified supplier (instance tags + synthetic role tag) works end-to-end via A2A.
        String channelName = "a2a-acl-pass-" + UUID.randomUUID();
        tools.createChannel(channelName, "ACL pass", "APPEND",
                null, "role:human", null, null, null, null, null, null, null, null);

        assertDoesNotThrow(() ->
                a2aBackend.receive(channelName, "user", "allowed request", null, Map.of(), null));
    }

    @Test
    void close_thenEnsureRegistered_registersAgain() {
        tools.createChannel("a2a-backend-lifecycle-1", "Test", "APPEND", null, null, null, null, null, null, null, null, null, null);
        Channel ch = channelService.findByName("a2a-backend-lifecycle-1").orElseThrow();
        ChannelRef ref = new ChannelRef(ch.id, "a2a-backend-lifecycle-1");

        a2aBackend.ensureRegistered(ch.id, ref);
        a2aBackend.close(ref);
        // After close, ensureRegistered should re-register (channel removed from set)
        a2aBackend.ensureRegistered(ch.id, ref);

        long a2aCount = channelGateway.listBackends(ch.id).stream()
                .filter(b -> "a2a".equals(b.backendId()))
                .count();
        // The backend is re-registered after close
        assertTrue(a2aCount >= 1);
    }
}

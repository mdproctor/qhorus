package io.casehub.qhorus.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
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
        tools.createChannel("a2a-backend-reg-1", "Test", "APPEND", null, null, null, null, null, null);
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
        tools.createChannel("a2a-backend-recv-1", "Test", "APPEND", null, null, null, null, null, null);
        String correlationId = UUID.randomUUID().toString();

        String returned = a2aBackend.receive("a2a-backend-recv-1", "agent",
                "work result", correlationId, Map.of(), null);

        assertEquals(correlationId, returned, "receive() should return the provided correlationId");
        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-1", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size(), "exactly one message should be created");
        assertEquals("RESPONSE", check.messages().get(0).messageType(), "agent role should produce RESPONSE type");
        assertEquals("agent", check.messages().get(0).sender(), "agent sender should be 'agent'");
    }

    @Test
    void receive_roleUserNoSignals_createsQueryWithHumanSender() {
        tools.createChannel("a2a-backend-recv-2", "Test", "APPEND", null, null, null, null, null, null);

        a2aBackend.receive("a2a-backend-recv-2", "user",
                "help me", null, Map.of(), null);

        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-2", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size(), "exactly one message should be created");
        assertEquals("QUERY", check.messages().get(0).messageType(), "user role should produce QUERY type");
        assertEquals("human:user", check.messages().get(0).sender(), "user role should produce human: sender");
    }

    @Test
    void receive_noTaskId_generatesCorrelationId() {
        tools.createChannel("a2a-backend-recv-4", "Test", "APPEND", null, null, null, null, null, null);

        String correlationId = a2aBackend.receive("a2a-backend-recv-4", "user",
                "hello", null, Map.of(), null);

        assertNotNull(correlationId, "auto-generated correlationId must not be null");
        assertFalse(correlationId.isBlank(), "auto-generated correlationId must not be blank");
        assertDoesNotThrow(() -> UUID.fromString(correlationId),
                "auto-generated correlationId should be a valid UUID");
    }

    @Test
    void post_logsOnly_doesNotThrow() {
        // post() is a logging no-op; must not throw for any message type
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "test");
        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(),
                "agent",
                MessageType.RESPONSE,
                "content",
                UUID.randomUUID(),
                ActorType.AGENT);

        assertDoesNotThrow(() -> a2aBackend.post(ref, message));
    }

    @Test
    void receive_roleUserWithSystemHeader_createsSenderSystem() {
        tools.createChannel("a2a-backend-recv-sys-1", "Test", "APPEND", null, null, null, null, null, null);

        a2aBackend.receive("a2a-backend-recv-sys-1", "user",
                "scheduled check", null, Map.of(), "SYSTEM");

        QhorusMcpTools.CheckResult check = tools.checkMessages("a2a-backend-recv-sys-1", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size());
        assertEquals("system", check.messages().get(0).sender());
    }

    @Test
    void close_thenEnsureRegistered_registersAgain() {
        tools.createChannel("a2a-backend-lifecycle-1", "Test", "APPEND", null, null, null, null, null, null);
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

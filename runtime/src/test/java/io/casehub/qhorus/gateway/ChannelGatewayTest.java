package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.*;
import io.casehub.qhorus.runtime.message.MessageService;

class ChannelGatewayTest {

    /** Minimal local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private volatile RuntimeException throwOnPost;

        RecordingBackend(String id, ActorType actorType) {
            this.id = id;
            this.actorType = actorType;
        }

        void throwOnNextPost(RuntimeException ex) { this.throwOnPost = ex; }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            RuntimeException ex = throwOnPost;
            if (ex != null) { throwOnPost = null; throw ex; }
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    }

    MessageService messageService;
    QhorusChannelBackend agentBackend;
    DefaultInboundNormaliser normaliser;
    ChannelGateway gateway;

    UUID channelId;
    ChannelRef channelRef;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        agentBackend = new QhorusChannelBackend(messageService);
        normaliser = new DefaultInboundNormaliser();
        gateway = new ChannelGateway(agentBackend, normaliser, messageService);
        channelId = UUID.randomUUID();
        channelRef = new ChannelRef(channelId, "test-channel");
        gateway.initChannel(channelId, channelRef);
    }

    // ── Registration ──────────────────────────────────────────────────────

    @Test
    void listBackends_includesQhorusInternalByDefault() {
        List<ChannelGateway.BackendRegistration> backends = gateway.listBackends(channelId);
        assertEquals(1, backends.size());
        assertEquals("qhorus-internal", backends.get(0).backendId());
        assertEquals("agent", backends.get(0).backendType());
    }

    @Test
    void registerObserver_appearsInList() {
        RecordingBackend observer = new RecordingBackend("slack-obs", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");
        assertEquals(2, gateway.listBackends(channelId).size());
    }

    @Test
    void registerSecondParticipating_throws() {
        RecordingBackend first = new RecordingBackend("whatsapp-1", ActorType.HUMAN);
        RecordingBackend second = new RecordingBackend("slack-1", ActorType.HUMAN);
        gateway.registerBackend(channelId, first, "human_participating");

        assertThrows(DuplicateParticipatingBackendException.class,
                () -> gateway.registerBackend(channelId, second, "human_participating"));
    }

    @Test
    void deregisterQhorusInternal_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> gateway.deregisterBackend(channelId, "qhorus-internal"));
    }

    @Test
    void deregisterBackend_removesFromList() {
        RecordingBackend observer = new RecordingBackend("slack-obs", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");
        gateway.deregisterBackend(channelId, "slack-obs");
        assertEquals(1, gateway.listBackends(channelId).size());
    }

    // ── Outbound ──────────────────────────────────────────────────────────

    @Test
    void post_callsAgentBackendSynchronously() {
        UUID corrId = UUID.randomUUID();
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.COMMAND, "do it", corrId, ActorType.AGENT);

        gateway.post(channelId, msg);

        verify(messageService).send(eq(channelId), eq("agent-a"), eq(MessageType.COMMAND),
                eq("do it"), eq(corrId.toString()), isNull());
    }

    @Test
    void post_fansOutToObserverBackend() throws Exception {
        RecordingBackend observer = new RecordingBackend("panel", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.EVENT, "tool used", null, ActorType.AGENT);
        gateway.post(channelId, msg);

        // Fan-out is async via virtual threads — give time to execute
        Thread.sleep(200);
        assertEquals(1, observer.posts().size());
        assertEquals("tool used", observer.posts().get(0).content());
    }

    @Test
    void post_observerFailure_doesNotPropagate() throws Exception {
        RecordingBackend failingBackend = new RecordingBackend("bad", ActorType.HUMAN);
        failingBackend.throwOnNextPost(new RuntimeException("network error"));
        gateway.registerBackend(channelId, failingBackend, "human_observer");

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.STATUS, "still working", null, ActorType.AGENT);

        assertDoesNotThrow(() -> gateway.post(channelId, msg));
        Thread.sleep(200);
    }

    // ── Inbound ───────────────────────────────────────────────────────────

    @Test
    void receiveHumanMessage_callsMessageServiceWithHumanSender() {
        InboundHumanMessage raw = new InboundHumanMessage(
                "user-42", "Can you stop?", Instant.now(), Map.of());

        gateway.receiveHumanMessage(channelRef, raw);

        verify(messageService).send(eq(channelId), eq("human:user-42"),
                eq(MessageType.QUERY), eq("Can you stop?"), isNull(), isNull());
    }

    @Test
    void receiveObserverSignal_forcesEvent() {
        ObserverSignal signal = new ObserverSignal(
                "panel-user", "thumbs up", Instant.now(), Map.of());

        gateway.receiveObserverSignal(channelRef, signal);

        verify(messageService).send(eq(channelId), eq("human:panel-user"),
                eq(MessageType.EVENT), eq("thumbs up"), isNull(), isNull());
    }
}

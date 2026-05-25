package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;

class ChannelGatewayTest {

    /** Minimal local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private final List<ChannelRef> channelRefs = Collections.synchronizedList(new ArrayList<>());
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
            channelRefs.add(channel);
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
        List<ChannelRef> channelRefs() { return Collections.unmodifiableList(channelRefs); }
    }

    MessageService messageService;
    QhorusChannelBackend agentBackend;
    DefaultInboundNormaliser normaliser;
    ChannelGateway gateway;

    UUID channelId;
    ChannelRef channelRef;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        // QhorusChannelBackend has no MessageService dependency — it is a registry anchor only
        agentBackend = new QhorusChannelBackend();
        normaliser = new DefaultInboundNormaliser();
        gateway = new ChannelGateway(agentBackend, normaliser, messageService,
                mock(ChannelService.class), mock(Event.class));
        channelId = UUID.randomUUID();
        channelRef = new ChannelRef(channelId, "test-channel");
        gateway.initChannel(channelId, channelRef);
    }

    // ── Registration ──────────────────────────────────────────────────────────

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

    // ── fanOut ────────────────────────────────────────────────────────────────

    @Test
    void fanOut_skipsQhorusInternalBackend() {
        // QhorusChannelBackend.post() is a no-op and fanOut() explicitly skips it —
        // verify that fanOut does not call it for the qhorus-internal slot
        QhorusChannelBackend spy = spy(agentBackend);
        // Re-init gateway with spy to observe post() calls
        ChannelGateway gw2 = new ChannelGateway(spy, normaliser, messageService,
                mock(ChannelService.class), mock(Event.class));
        UUID ch2 = UUID.randomUUID();
        gw2.initChannel(ch2, new ChannelRef(ch2, "ch2"));

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.COMMAND, "do it", null, null, ActorType.AGENT);
        gw2.fanOut(ch2, "ch2", msg);

        verify(spy, never()).post(any(), any());
    }

    @Test
    void fanOut_deliversToRegisteredExternalBackend() throws Exception {
        RecordingBackend observer = new RecordingBackend("panel", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.EVENT, "tool used", null, null, ActorType.AGENT);
        gateway.fanOut(channelId, "my-channel", msg);

        // Fan-out is async via virtual threads — give time to execute
        Thread.sleep(200);
        assertEquals(1, observer.posts().size());
        assertEquals("tool used", observer.posts().get(0).content());
    }

    @Test
    void fanOut_carriesInReplyToInOutboundMessage() throws Exception {
        RecordingBackend observer = new RecordingBackend("panel-reply", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");

        final Long replyToId = 42L;
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "worker",
                MessageType.DONE, "task complete", UUID.randomUUID(), replyToId, ActorType.AGENT);
        gateway.fanOut(channelId, "my-channel", msg);

        Thread.sleep(200);
        assertEquals(1, observer.posts().size());
        assertEquals(replyToId, observer.posts().get(0).inReplyTo());
    }

    @Test
    void fanOut_passesHumanReadableNameToBackend() throws Exception {
        RecordingBackend observer = new RecordingBackend("panel-name-check", ActorType.HUMAN);
        gateway.registerBackend(channelId, observer, "human_observer");

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.STATUS, "working", null, null, ActorType.AGENT);
        gateway.fanOut(channelId, "case-abc/work", msg);

        Thread.sleep(200);
        assertEquals(1, observer.posts().size());
        // ChannelRef received by backend must carry the human-readable name, not channelId.toString()
        assertEquals("case-abc/work", observer.channelRefs().get(0).name());
    }

    @Test
    void fanOut_observerFailure_doesNotPropagate() throws Exception {
        RecordingBackend failingBackend = new RecordingBackend("bad", ActorType.HUMAN);
        failingBackend.throwOnNextPost(new RuntimeException("network error"));
        gateway.registerBackend(channelId, failingBackend, "human_observer");

        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.STATUS, "still working", null, null, ActorType.AGENT);

        assertDoesNotThrow(() -> gateway.fanOut(channelId, "some-channel", msg));
        Thread.sleep(200);
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    @Test
    void receiveHumanMessage_callsMessageServiceDispatchWithHumanSender() {
        InboundHumanMessage raw = new InboundHumanMessage(
                "user-42", "Can you stop?", Instant.now(), Map.of(), null, null);

        gateway.receiveHumanMessage(channelRef, raw);

        verify(messageService).dispatch(argThat(d ->
                d.channelId().equals(channelId)
                        && "human:user-42".equals(d.sender())
                        && d.type() == MessageType.QUERY
                        && "Can you stop?".equals(d.content())
                        && d.correlationId() == null
                        && d.actorType() == ActorType.HUMAN
        ));
    }

    @Test
    void receiveHumanMessage_withCorrelationId_passesCorrelationIdToMessageService() {
        InboundHumanMessage raw = new InboundHumanMessage(
                "user-42", "approved", Instant.now(), Map.of(), "corr-abc", null);

        gateway.receiveHumanMessage(channelRef, raw);

        verify(messageService).dispatch(argThat(d ->
                "human:user-42".equals(d.sender())
                        && "corr-abc".equals(d.correlationId())
        ));
    }

    @Test
    void receiveHumanMessage_uses_backend_normaliser_when_provided() {
        InboundNormaliser customNormaliser = (ch, raw) -> new NormalisedMessage(
                MessageType.RESPONSE, raw.content(),
                "human:" + raw.externalSenderId(),
                raw.correlationId(), raw.inReplyTo(), null, null);

        HumanParticipatingChannelBackend customBackend = new HumanParticipatingChannelBackend() {
            @Override public String backendId()    { return "custom-backend"; }
            @Override public ActorType actorType() { return ActorType.HUMAN; }
            @Override public void open(ChannelRef ch, Map<String, String> m) {}
            @Override public void post(ChannelRef ch, OutboundMessage msg)   {}
            @Override public void close(ChannelRef ch) {}
            @Override public InboundNormaliser normaliser() { return customNormaliser; }
        };
        gateway.registerBackend(channelId, customBackend, "human_participating");

        InboundHumanMessage raw = new InboundHumanMessage(
                "user-1", "task complete", Instant.now(), Map.of(), "corr-42", 99L);
        gateway.receiveHumanMessage(channelRef, raw);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.RESPONSE);
        assertThat(captor.getValue().inReplyTo()).isEqualTo(99L);
    }

    @Test
    void receiveHumanMessage_falls_back_to_system_default_when_backend_normaliser_is_null() {
        HumanParticipatingChannelBackend nullNormaliserBackend = new HumanParticipatingChannelBackend() {
            @Override public String backendId()    { return "null-normaliser-backend"; }
            @Override public ActorType actorType() { return ActorType.HUMAN; }
            @Override public void open(ChannelRef ch, Map<String, String> m) {}
            @Override public void post(ChannelRef ch, OutboundMessage msg)   {}
            @Override public void close(ChannelRef ch) {}
            // normaliser() returns null (default) — system DefaultInboundNormaliser should be used
        };
        gateway.registerBackend(channelId, nullNormaliserBackend, "human_participating");

        // Pass message-type metadata so we can prove the system normaliser processed it
        // (only DefaultInboundNormaliser reads this key; a custom normaliser would ignore it)
        InboundHumanMessage raw = new InboundHumanMessage(
                "user-1", "progress update", Instant.now(), Map.of("message-type", "STATUS"), null, null);
        gateway.receiveHumanMessage(channelRef, raw);

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        // DefaultInboundNormaliser reads message-type metadata → STATUS
        assertThat(captor.getValue().type()).isEqualTo(MessageType.STATUS);
    }

    @Test
    void receiveObserverSignal_forcesEvent() {
        ObserverSignal signal = new ObserverSignal(
                "panel-user", "thumbs up", Instant.now(), Map.of());

        gateway.receiveObserverSignal(channelRef, signal);

        verify(messageService).dispatch(argThat(d ->
                "human:panel-user".equals(d.sender())
                        && d.type() == MessageType.EVENT
                        && "thumbs up".equals(d.content())
                        && d.actorType() == ActorType.HUMAN
        ));
    }
}

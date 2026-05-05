package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.ObserverSignal;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DuplicateParticipatingBackendException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class ChannelGatewayRobustnessTest {

    /** Minimal local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private final List<ChannelRef> closes = Collections.synchronizedList(new ArrayList<>());
        private volatile RuntimeException throwOnPost;

        RecordingBackend(String id, ActorType actorType) { this.id = id; this.actorType = actorType; }
        void throwOnNextPost(RuntimeException ex) { this.throwOnPost = ex; }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            if (throwOnPost != null) { RuntimeException ex = throwOnPost; throwOnPost = null; throw ex; }
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) { closes.add(channel); }

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
        List<ChannelRef> closes() { return Collections.unmodifiableList(closes); }
    }

    @Inject QhorusMcpTools tools;
    @Inject ChannelGateway gateway;

    @Test
    @TestTransaction
    void duplicateParticipatingBackend_isRejected() {
        tools.createChannel("rob-dup-1", "test", "append", null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> "rob-dup-1".equals(c.name())).findFirst().orElseThrow();
        gateway.registerBackend(ch.channelId(),
                new RecordingBackend("whatsapp-1", ActorType.HUMAN), "human_participating");

        assertThrows(DuplicateParticipatingBackendException.class,
                () -> gateway.registerBackend(ch.channelId(),
                        new RecordingBackend("slack-1", ActorType.HUMAN),
                        "human_participating"));
    }

    @Test
    void observerFailure_doesNotFailSendMessage() throws Exception {
        // Channel setup must be in a committed transaction before sendMessage runs
        String channelName = "rob-obs-fail";
        createChannelCommitted(channelName);

        var ch = tools.listChannels().stream()
                .filter(c -> channelName.equals(c.name())).findFirst().orElseThrow();
        RecordingBackend failing = new RecordingBackend("failing-obs", ActorType.HUMAN);
        failing.throwOnNextPost(new RuntimeException("network error"));
        gateway.registerBackend(ch.channelId(), failing, "human_observer");

        assertDoesNotThrow(() ->
                tools.sendMessage(channelName, "agent-a", "event",
                        "test", null, null, null, null, null));
        Thread.sleep(300);
    }

    @Test
    @TestTransaction
    void twoChannels_backendRegistrationsAreIsolated() {
        tools.createChannel("rob-iso-1", "test1", "append", null, null, null, null, null, null);
        tools.createChannel("rob-iso-2", "test2", "append", null, null, null, null, null, null);
        var ch1 = tools.listChannels().stream()
                .filter(c -> "rob-iso-1".equals(c.name())).findFirst().orElseThrow();
        var ch2 = tools.listChannels().stream()
                .filter(c -> "rob-iso-2".equals(c.name())).findFirst().orElseThrow();

        RecordingBackend obs = new RecordingBackend("ch1-only", ActorType.HUMAN);
        gateway.registerBackend(ch1.channelId(), obs, "human_observer");

        assertEquals(1, gateway.listBackends(ch2.channelId()).size());
    }

    @Test
    @TestTransaction
    void receiveHumanMessage_createsMessageWithHumanSender() {
        tools.createChannel("rob-human-msg", "test", "append", null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> "rob-human-msg".equals(c.name())).findFirst().orElseThrow();
        ChannelRef ref = new ChannelRef(ch.channelId(), "rob-human-msg");
        InboundHumanMessage raw = new InboundHumanMessage(
                "user-42", "Can you stop the analysis?", Instant.now(), Map.of());

        gateway.receiveHumanMessage(ref, raw);

        var messages = tools.checkMessages("rob-human-msg", null, null, null, null, true);
        assertTrue(messages.messages().stream()
                .anyMatch(m -> "human:user-42".equals(m.sender())));
    }

    @Test
    @TestTransaction
    void receiveObserverSignal_forcesEventType() {
        tools.createChannel("rob-obs-sig", "test", "append", null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> "rob-obs-sig".equals(c.name())).findFirst().orElseThrow();
        ChannelRef ref = new ChannelRef(ch.channelId(), "rob-obs-sig");
        ObserverSignal signal = new ObserverSignal(
                "panel-user", "thumbs-up", Instant.now(), Map.of());

        gateway.receiveObserverSignal(ref, signal);

        var messages = tools.checkMessages("rob-obs-sig", null, null, null, null, true);
        assertTrue(messages.messages().stream()
                .anyMatch(m -> "human:panel-user".equals(m.sender())
                        && "event".equalsIgnoreCase(m.messageType())));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void createChannelCommitted(String name) {
        tools.createChannel(name, "test", "append", null, null, null, null, null, null);
    }
}

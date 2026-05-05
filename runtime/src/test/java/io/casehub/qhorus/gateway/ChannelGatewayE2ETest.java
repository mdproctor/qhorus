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
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class ChannelGatewayE2ETest {

    /** Minimal local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private final List<ChannelRef> closes = Collections.synchronizedList(new ArrayList<>());

        RecordingBackend(String id, ActorType actorType) { this.id = id; this.actorType = actorType; }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) { posts.add(message); }
        @Override public void close(ChannelRef channel) { closes.add(channel); }

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
        List<ChannelRef> closes() { return Collections.unmodifiableList(closes); }
    }

    @Inject QhorusMcpTools tools;
    @Inject ChannelGateway gateway;

    @Test
    void agentPosts_observerReceives_fullFanOut() throws Exception {
        // Channel must be committed so sendMessage (REQUIRES_NEW ledger) can see it
        String channelName = "e2e-gw-fanout";
        createChannelCommitted(channelName);

        var ch = tools.listChannels().stream()
                .filter(c -> channelName.equals(c.name())).findFirst().orElseThrow();
        RecordingBackend observer = new RecordingBackend("panel", ActorType.HUMAN);
        gateway.registerBackend(ch.channelId(), observer, "human_observer");

        tools.sendMessage(channelName, "agent-a", "event",
                "analysis_complete", null, null, null, null, null);

        Thread.sleep(300);
        assertEquals(1, observer.posts().size());
        assertEquals("analysis_complete", observer.posts().get(0).content());
    }

    @Test
    @TestTransaction
    void humanReplies_viaParticipatingBackend_appearsInChannel() {
        String channelName = "e2e-gw-reply";
        tools.createChannel(channelName, "E2E gateway test", "append",
                null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> channelName.equals(c.name())).findFirst().orElseThrow();
        ChannelRef ref = new ChannelRef(ch.channelId(), channelName);
        InboundHumanMessage reply = new InboundHumanMessage(
                "whatsapp-99", "Please stop and summarise", Instant.now(), Map.of());

        gateway.receiveHumanMessage(ref, reply);

        var messages = tools.checkMessages(channelName, null, null, null, null, true);
        assertTrue(messages.messages().stream()
                .anyMatch(m -> "human:whatsapp-99".equals(m.sender())));
    }

    @Test
    @TestTransaction
    void observerSignal_appearsAsEvent_notSpeechAct() {
        String channelName = "e2e-gw-signal";
        tools.createChannel(channelName, "E2E gateway test", "append",
                null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> channelName.equals(c.name())).findFirst().orElseThrow();
        ChannelRef ref = new ChannelRef(ch.channelId(), channelName);
        ObserverSignal signal = new ObserverSignal(
                "dashboard-user", "flag:urgent", Instant.now(), Map.of());

        gateway.receiveObserverSignal(ref, signal);

        var messages = tools.checkMessages(channelName, null, null, null, null, true);
        var eventMsg = messages.messages().stream()
                .filter(m -> "human:dashboard-user".equals(m.sender()))
                .findFirst();
        assertTrue(eventMsg.isPresent());
        assertEquals("event", eventMsg.get().messageType().toLowerCase());
    }

    @Test
    void deleteChannel_deregistersAllBackendsAndCallsClose() {
        // Channel must be committed so delete_channel's own transaction can see it
        String channelName = "e2e-gw-delete";
        createChannelCommitted(channelName);

        var ch = tools.listChannels().stream()
                .filter(c -> channelName.equals(c.name())).findFirst().orElseThrow();
        RecordingBackend obs = new RecordingBackend("to-close", ActorType.HUMAN);
        gateway.registerBackend(ch.channelId(), obs, "human_observer");

        tools.deleteChannel(channelName, false, null);

        assertEquals(1, obs.closes().size());
        assertTrue(gateway.listBackends(ch.channelId()).isEmpty());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void createChannelCommitted(String name) {
        tools.createChannel(name, "E2E gateway test", "append", null, null, null, null, null, null);
    }
}

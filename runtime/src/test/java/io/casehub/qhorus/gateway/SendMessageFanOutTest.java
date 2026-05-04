package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests verifying that ChannelGateway is correctly wired into the MCP tools:
 * - createChannel calls initChannel (qhorus-internal backend is registered)
 * - deleteChannel calls closeChannel (all backends deregistered)
 * - sendMessage calls fanOut (external backends receive the message)
 * - listBackends and deregisterBackend MCP tools work end-to-end
 *
 * Refs #131 #138
 */
@QuarkusTest
class SendMessageFanOutTest {

    /** Local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());

        RecordingBackend(String id, ActorType actorType) {
            this.id = id;
            this.actorType = actorType;
        }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) { posts.add(message); }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    }

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelGateway gateway;

    @BeforeEach
    @Transactional
    void setUp() {
        tools.createChannel("fanout-1", "test", "append", null, null, null, null, null, null);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Use force=true so messages created during tests are deleted too
        tools.deleteChannel("fanout-1", true, null);
    }

    @Test
    void sendMessage_stillWorks() {
        var result = tools.sendMessage("fanout-1", "agent-a", "command",
                "do the thing", null, null, null, null, null);
        assertNotNull(result);
        assertEquals("fanout-1", result.channelName());
    }

    @Test
    void sendMessage_fansOutToObserver() throws Exception {
        var ch = tools.listChannels().stream()
                .filter(c -> "fanout-1".equals(c.name())).findFirst().orElseThrow();
        RecordingBackend observer = new RecordingBackend("test-obs", ActorType.HUMAN);
        gateway.registerBackend(ch.channelId(), observer, "human_observer");

        tools.sendMessage("fanout-1", "agent-a", "event", "tool_done", null, null, null, null, null);

        Thread.sleep(300);
        assertEquals(1, observer.posts().size());
        assertEquals("tool_done", observer.posts().get(0).content());
    }

    @Test
    @Transactional
    void createChannel_autoRegistersQhorusInternal() {
        var ch = tools.listChannels().stream()
                .filter(c -> "fanout-1".equals(c.name())).findFirst().orElseThrow();
        var backends = gateway.listBackends(ch.channelId());
        assertEquals(1, backends.size());
        assertEquals("qhorus-internal", backends.get(0).backendId());
    }

    @Test
    @Transactional
    void deleteChannel_deregistersAllBackends() {
        tools.createChannel("fanout-2", "test2", "append", null, null, null, null, null, null);
        var ch = tools.listChannels().stream()
                .filter(c -> "fanout-2".equals(c.name())).findFirst().orElseThrow();
        UUID channelId = ch.channelId();
        tools.deleteChannel("fanout-2", true, null);
        assertTrue(gateway.listBackends(channelId).isEmpty());
    }

    @Test
    void listBackends_returnsQhorusInternal() {
        var result = tools.listBackends("fanout-1");
        assertEquals(1, result.size());
        assertEquals("qhorus-internal", result.get(0).backendId());
        assertEquals("agent", result.get(0).backendType());
    }

    @Test
    @Transactional
    void deregisterBackend_removesObserver() {
        var ch = tools.listChannels().stream()
                .filter(c -> "fanout-1".equals(c.name())).findFirst().orElseThrow();
        RecordingBackend obs = new RecordingBackend("to-remove", ActorType.HUMAN);
        gateway.registerBackend(ch.channelId(), obs, "human_observer");

        tools.deregisterBackend("fanout-1", "to-remove");

        var backends = tools.listBackends("fanout-1");
        assertEquals(1, backends.size());
        assertEquals("qhorus-internal", backends.get(0).backendId());
    }
}

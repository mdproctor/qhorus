package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the {@code project_channel} MCP tool.
 *
 * <p>Messages written via {@link MessageStore#put} directly — bypasses
 * dispatch enforcement so tests focus on projection behaviour.
 *
 * <p>The test projection {@link SummaryBundle} is an {@code @ApplicationScoped}
 * bean in the test source set — no {@code @Alternative @Priority(1)} needed
 * because there is no production bean with the same {@code projectionName()}.
 * Quarkus CDI discovers it automatically. Refs qhorus#232.
 */
@QuarkusTest
@TestTransaction
class ProjectChannelToolIT {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageStore messageStore;

    // ── Test projection ───────────────────────────────────────────────────────

    /** Counts COMMAND messages and renders as "N command(s)". */
    @ApplicationScoped
    static class SummaryBundle implements RenderableProjection<Integer> {
        @Override public String projectionName() { return "it-summary"; }
        @Override public Integer identity() { return 0; }
        @Override public Integer apply(Integer s, MessageView m) {
            return m.type() == MessageType.COMMAND ? s + 1 : s;
        }
        @Override public String render(ProjectionResult<Integer> r) {
            return r.isEmpty() ? "empty" : r.state() + " command(s)";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createChannel() {
        Channel ch = new Channel();
        ch.name = "proj-it-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;
        ch.persist();
        return ch.id;
    }

    private void put(UUID channelId, MessageType type) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = "agent-a";
        m.messageType = type;
        m.actorType = ActorType.AGENT;
        m.content = "content";
        messageStore.put(m);
    }

    // ── Tests: channel resolution ─────────────────────────────────────────────

    @Test
    void projectChannel_byName_returnsRenderedResult() {
        UUID channelId = createChannel();
        put(channelId, MessageType.COMMAND);
        put(channelId, MessageType.STATUS);
        put(channelId, MessageType.COMMAND);

        Channel ch = Channel.findById(channelId);
        String result = tools.projectChannel(ch.name, "it-summary");
        assertThat(result).isEqualTo("2 command(s)");
    }

    @Test
    void projectChannel_byUUID_returnsRenderedResult() {
        UUID channelId = createChannel();
        put(channelId, MessageType.COMMAND);

        String result = tools.projectChannel(channelId.toString(), "it-summary");
        assertThat(result).isEqualTo("1 command(s)");
    }

    @Test
    void projectChannel_emptyChannel_rendersEmptyCase() {
        UUID channelId = createChannel();

        Channel ch = Channel.findById(channelId);
        String result = tools.projectChannel(ch.name, "it-summary");
        assertThat(result).isEqualTo("empty");
    }

    // ── Tests: error cases ────────────────────────────────────────────────────

    @Test
    void projectChannel_unknownProjectionName_throwsToolCallException() {
        UUID channelId = createChannel();
        Channel ch = Channel.findById(channelId);

        assertThrows(ToolCallException.class,
                () -> tools.projectChannel(ch.name, "no-such-projection"));
    }

    @Test
    void projectChannel_unknownChannelName_throwsToolCallException() {
        assertThrows(ToolCallException.class,
                () -> tools.projectChannel("channel-that-does-not-exist", "it-summary"));
    }

    @Test
    void projectChannel_nonExistentUUID_throwsToolCallException() {
        String fakeUuid = UUID.randomUUID().toString();

        assertThrows(ToolCallException.class,
                () -> tools.projectChannel(fakeUuid, "it-summary"));
    }

    // ── Tests: LAST_WRITE channel ─────────────────────────────────────────────

    @Test
    void projectChannel_lastWriteChannel_projectionSucceeds() {
        // LAST_WRITE overwrite semantics are enforced by MessageService.dispatch() —
        // not by messageStore.put(). This test verifies projection works on a LAST_WRITE
        // channel; the single-message-per-sender guarantee requires dispatch() (enforcement gate).
        Channel ch = new Channel();
        ch.name = "lw-it-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.LAST_WRITE;
        ch.persist();
        UUID channelId = ch.id;

        put(channelId, MessageType.COMMAND);

        String result = tools.projectChannel(ch.name, "it-summary");
        assertThat(result).isEqualTo("1 command(s)");
    }

    // ── Tests: paused channel reads proceed ───────────────────────────────────

    @Test
    void projectChannel_pausedChannel_readsSucceed() {
        UUID channelId = createChannel();
        put(channelId, MessageType.COMMAND);
        Channel ch = Channel.findById(channelId);
        ch.paused = true;
        ch.persist();

        // Projection is a read — must succeed even on paused channels
        String result = tools.projectChannel(ch.name, "it-summary");
        assertThat(result).isEqualTo("1 command(s)");
    }
}

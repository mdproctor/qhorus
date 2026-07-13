package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class ProjectChannelToolIT {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageStore messageStore;

    @Inject
    ChannelStore channelStore;

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

    private Channel createChannel(ChannelSemantic semantic) {
        return channelStore.put(Channel.builder("proj-it-" + UUID.randomUUID())
                .semantic(semantic).build());
    }

    private Channel createChannel() {
        return createChannel(ChannelSemantic.APPEND);
    }

    private void put(UUID channelId, MessageType type) {
        messageStore.put(Message.builder()
                .channelId(channelId).sender("agent-a").messageType(type)
                .actorType(ActorType.AGENT).content("content").build());
    }

    @Test
    void projectChannel_byName_returnsRenderedResult() {
        Channel ch = createChannel();
        put(ch.id(), MessageType.COMMAND);
        put(ch.id(), MessageType.STATUS);
        put(ch.id(), MessageType.COMMAND);

        String result = tools.projectChannel(ch.name(), "it-summary", null, null);
        assertThat(result).isEqualTo("2 command(s)");
    }

    @Test
    void projectChannel_byUUID_returnsRenderedResult() {
        Channel ch = createChannel();
        put(ch.id(), MessageType.COMMAND);

        String result = tools.projectChannel(ch.id().toString(), "it-summary", null, null);
        assertThat(result).isEqualTo("1 command(s)");
    }

    @Test
    void projectChannel_emptyChannel_rendersEmptyCase() {
        Channel ch = createChannel();

        String result = tools.projectChannel(ch.name(), "it-summary", null, null);
        assertThat(result).isEqualTo("empty");
    }

    @Test
    void projectChannel_unknownProjectionName_throwsToolCallException() {
        Channel ch = createChannel();

        assertThrows(ToolCallException.class,
                () -> tools.projectChannel(ch.name(), "no-such-projection", null, null));
    }

    @Test
    void projectChannel_unknownChannelName_throwsToolCallException() {
        assertThrows(ToolCallException.class,
                () -> tools.projectChannel("channel-that-does-not-exist", "it-summary", null, null));
    }

    @Test
    void projectChannel_nonExistentUUID_throwsToolCallException() {
        String fakeUuid = UUID.randomUUID().toString();

        assertThrows(ToolCallException.class,
                () -> tools.projectChannel(fakeUuid, "it-summary", null, null));
    }

    @Test
    void projectChannel_lastWriteChannel_projectionSucceeds() {
        Channel ch = createChannel(ChannelSemantic.LAST_WRITE);
        put(ch.id(), MessageType.COMMAND);

        String result = tools.projectChannel(ch.name(), "it-summary", null, null);
        assertThat(result).isEqualTo("1 command(s)");
    }

    @Test
    void projectChannel_pausedChannel_readsSucceed() {
        Channel ch = createChannel();
        put(ch.id(), MessageType.COMMAND);
        channelStore.put(ch.toBuilder().paused(true).build());

        String result = tools.projectChannel(ch.name(), "it-summary", null, null);
        assertThat(result).isEqualTo("1 command(s)");
    }
}

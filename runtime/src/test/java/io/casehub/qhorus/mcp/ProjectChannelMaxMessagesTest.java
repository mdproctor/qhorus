package io.casehub.qhorus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@code max_messages} parameter on {@code project_channel}.
 * Refs qhorus#239.
 */
@QuarkusTest
class ProjectChannelMaxMessagesTest {

    @Inject QhorusMcpTools tools;

    /** A simple counting projection registered for this test. */
    @ApplicationScoped
    static class CountingProjection implements RenderableProjection<Integer> {
        @Override
        public String projectionName() {
            return "message-counter";
        }

        @Override
        public Integer identity() {
            return 0;
        }

        @Override
        public Integer apply(Integer state, io.casehub.qhorus.api.message.MessageView msg) {
            return state + 1;
        }

        @Override
        public String render(ProjectionResult<Integer> result) {
            return "count=" + result.state();
        }
    }

    @Test
    @TestTransaction
    void maxMessages_limitsMessagesInFold() {
        String channelName = "fold-limit-" + System.nanoTime();
        tools.createChannel(channelName, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // Send 5 messages
        for (int i = 0; i < 5; i++) {
            tools.sendMessage(channelName, "agent-1", MessageType.STATUS.name(), "msg-" + i,
                    null, null, null, null, null, null, null, null);
        }

        // Fold only the first 2
        String result = tools.projectChannel(channelName, "message-counter", 2, null);

        assertThat(result).isEqualTo("count=2");
    }

    @Test
    @TestTransaction
    void nullMaxMessages_foldsAll() {
        String channelName = "fold-all-" + System.nanoTime();
        tools.createChannel(channelName, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        for (int i = 0; i < 4; i++) {
            tools.sendMessage(channelName, "agent-1", MessageType.STATUS.name(), "msg-" + i,
                    null, null, null, null, null, null, null, null);
        }

        String result = tools.projectChannel(channelName, "message-counter", null, null);

        assertThat(result).isEqualTo("count=4");
    }

    @Test
    @TestTransaction
    void nonPositiveMaxMessages_foldsAll() {
        String channelName = "fold-nonpos-" + System.nanoTime();
        tools.createChannel(channelName, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        for (int i = 0; i < 3; i++) {
            tools.sendMessage(channelName, "agent-1", MessageType.STATUS.name(), "msg-" + i,
                    null, null, null, null, null, null, null, null);
        }

        String result = tools.projectChannel(channelName, "message-counter", -1, null);

        assertThat(result).isEqualTo("count=3");
    }
}

package io.casehub.qhorus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ProjectChannelTopicTest {

    @Inject QhorusMcpTools tools;

    @ApplicationScoped
    static class TopicCounterProjection implements RenderableProjection<Integer> {
        @Override public String projectionName() { return "topic-counter"; }
        @Override public Integer identity() { return 0; }
        @Override public Integer apply(Integer state, MessageView msg) { return state + 1; }
        @Override public String render(ProjectionResult<Integer> result) {
            return result.isEmpty() ? "empty" : "count=" + result.state();
        }
    }

    @Test
    @TestTransaction
    void topicFilter_foldsOnlyMatchingTopic() {
        String ch = "proj-topic-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "testing");
        tools.sendMessage(ch, "carol", "status", "c", null, null, null, null, null, null, null, "design");

        String result = tools.projectChannel(ch, "topic-counter", null, "design");

        assertThat(result).isEqualTo("count=2");
    }

    @Test
    @TestTransaction
    void nullTopic_foldsAllMessages() {
        String ch = "proj-notopic-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "testing");

        String result = tools.projectChannel(ch, "topic-counter", null, null);

        assertThat(result).isEqualTo("count=2");
    }

    @Test
    @TestTransaction
    void blankTopic_normalizedToNull_foldsAll() {
        String ch = "proj-blank-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "testing");

        String result = tools.projectChannel(ch, "topic-counter", null, "  ");

        assertThat(result).isEqualTo("count=2");
    }

    @Test
    @TestTransaction
    void topicFilter_caseInsensitive() {
        String ch = "proj-case-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "Design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "testing");

        String result = tools.projectChannel(ch, "topic-counter", null, "design");

        assertThat(result).isEqualTo("count=1");
    }

    @Test
    @TestTransaction
    void topicFilter_noMatchingMessages_returnsEmpty() {
        String ch = "proj-nomatch-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");

        String result = tools.projectChannel(ch, "topic-counter", null, "nonexistent");

        assertThat(result).isEqualTo("empty");
    }

    @Test
    @TestTransaction
    void topicAndMaxMessages_bothApplied() {
        String ch = "proj-combined-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "carol", "status", "c", null, null, null, null, null, null, null, "design");

        String result = tools.projectChannel(ch, "topic-counter", 2, "design");

        assertThat(result).isEqualTo("count=2");
    }

    @Test
    @TestTransaction
    void topicOnly_noMaxMessages_scopedPathTaken() {
        String ch = "proj-topiconly-" + System.nanoTime();
        tools.createChannel(ch, "test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage(ch, "alice", "status", "a", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "bob", "status", "b", null, null, null, null, null, null, null, "testing");
        tools.sendMessage(ch, "carol", "status", "c", null, null, null, null, null, null, null, "design");
        tools.sendMessage(ch, "dave", "status", "d", null, null, null, null, null, null, null, "design");

        String result = tools.projectChannel(ch, "topic-counter", null, "design");

        assertThat(result).isEqualTo("count=3");
    }
}

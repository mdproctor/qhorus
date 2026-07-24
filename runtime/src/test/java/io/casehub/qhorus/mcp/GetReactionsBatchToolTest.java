package io.casehub.qhorus.mcp;

import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.ReactionGroup;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.message.ReactionService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class GetReactionsBatchToolTest {

    @Inject QhorusMcpTools tools;
    @Inject ReactionService reactionService;

    @Test
    @TestTransaction
    void batchReturnsGroupedReactions() {
        tools.createChannel("react-batch-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        DispatchResult msg1 = tools.sendMessage("react-batch-1", "agent-a", "status", "hello", null, null, null, null, null, null, null, null);
        DispatchResult msg2 = tools.sendMessage("react-batch-1", "agent-a", "status", "world", null, null, null, null, null, null, null, null);

        reactionService.react(msg1.messageId(), "👍", "user-1", null);
        reactionService.react(msg2.messageId(), "❤️", "user-2", null);

        Map<Long, List<ReactionGroup>> result = tools.getReactionsBatch(List.of(msg1.messageId(), msg2.messageId()));

        assertThat(result).containsKeys(msg1.messageId(), msg2.messageId());
        assertThat(result.get(msg1.messageId())).hasSize(1);
        assertThat(result.get(msg1.messageId()).get(0).emoji()).isEqualTo("👍");
        assertThat(result.get(msg2.messageId()).get(0).emoji()).isEqualTo("❤️");
    }

    @Test
    void emptyListRejected() {
        assertThatThrownBy(() -> tools.getReactionsBatch(List.of()))
                .isInstanceOf(io.quarkiverse.mcp.server.ToolCallException.class);
    }

    @Test
    void nullListRejected() {
        assertThatThrownBy(() -> tools.getReactionsBatch(null))
                .isInstanceOf(io.quarkiverse.mcp.server.ToolCallException.class);
    }

    @Test
    void oversizedListRejected() {
        var ids = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();
        assertThatThrownBy(() -> tools.getReactionsBatch(ids))
                .isInstanceOf(io.quarkiverse.mcp.server.ToolCallException.class);
    }

    @Test
    @TestTransaction
    void missingMessagesReturnEmptyMap() {
        Map<Long, List<ReactionGroup>> result = tools.getReactionsBatch(List.of(999999L));
        assertThat(result).containsKey(999999L);
        assertThat(result.get(999999L)).isEmpty();}
}

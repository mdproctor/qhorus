package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;

/**
 * Pure unit tests for RenderableProjection contract — no CDI, no Quarkus.
 * Verifies the interface contract: projectionName(), identity(), apply(), render().
 */
class RenderableProjectionTest {

    private static MessageView msg(MessageType type, String sender, String content) {
        return new MessageView(1L, UUID.randomUUID(), sender, type, content,
                null, null, null, null, ActorType.AGENT, Instant.now(), null, 0);
    }

    // A minimal RenderableProjection that counts COMMAND messages.
    private static final RenderableProjection<Integer> COMMAND_COUNTER =
            new RenderableProjection<>() {
                @Override public String projectionName() { return "command-counter"; }
                @Override public Integer identity() { return 0; }
                @Override public Integer apply(Integer state, MessageView message) {
                    return message.type() == MessageType.COMMAND ? state + 1 : state;
                }
                @Override public String render(ProjectionResult<Integer> result) {
                    if (result.isEmpty()) return "no messages";
                    return result.state() + " command(s)";
                }
            };

    @Test
    void projectionName_returnsNonNullNonEmpty() {
        assertThat(COMMAND_COUNTER.projectionName()).isNotNull().isNotEmpty();
    }

    @Test
    void identity_returnsNeutralElement() {
        assertThat(COMMAND_COUNTER.identity()).isEqualTo(0);
        assertThat(COMMAND_COUNTER.identity()).isEqualTo(0);
    }

    @Test
    void apply_countsCommandMessages() {
        Integer state = COMMAND_COUNTER.identity();
        state = COMMAND_COUNTER.apply(state, msg(MessageType.COMMAND, "agent-a", "run audit"));
        state = COMMAND_COUNTER.apply(state, msg(MessageType.STATUS, "agent-a", "working"));
        state = COMMAND_COUNTER.apply(state, msg(MessageType.COMMAND, "agent-b", "run check"));

        assertThat(state).isEqualTo(2);
    }

    @Test
    void render_emptyResult_returnsNonNull() {
        // ProjectionResult constructed directly here — safe for render() testing only.
        // The "never construct manually" contract applies to the incremental project() overload.
        ProjectionResult<Integer> empty = new ProjectionResult<>(COMMAND_COUNTER.identity(), null);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(COMMAND_COUNTER.render(empty)).isNotNull().isNotEmpty();
    }

    @Test
    void render_nonEmptyResult_producesExpectedOutput() {
        ProjectionResult<Integer> result = new ProjectionResult<>(3, 42L);
        assertThat(COMMAND_COUNTER.render(result)).isEqualTo("3 command(s)");
    }

    @Test
    void render_identityState_withNonEmptyResult_distinguishableFromEmpty() {
        // A channel with only STATUS messages: fold produces identity (0)
        // but the result is NOT empty — lastMessageId is non-null.
        ProjectionResult<Integer> noCommandsButNotEmpty = new ProjectionResult<>(0, 7L);
        assertThat(noCommandsButNotEmpty.isEmpty()).isFalse();

        // Renderer can detect this is NOT the empty-channel case via isEmpty()
        String rendered = COMMAND_COUNTER.render(noCommandsButNotEmpty);
        assertThat(rendered).isEqualTo("0 command(s)");  // not "no messages"
    }
}

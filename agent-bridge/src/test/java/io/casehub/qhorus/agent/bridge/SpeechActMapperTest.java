package io.casehub.qhorus.agent.bridge;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.agent.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechActMapperTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final String AGENT_ID = "code-reviewer-1";
    private static final String CORRELATION_ID = UUID.randomUUID().toString();

    private OutboundMessage inbound(MessageType type) {
        return new OutboundMessage(UUID.randomUUID(), 1L, "requester",
                type, "Review this code", null, CORRELATION_ID, null,
                ActorType.AGENT, List.of(), AGENT_ID, null);
    }

    @Test
    void mapToDispatch_commandCommitment_producesResponseOnly() {
        var stats = new AgentEvent.InvocationComplete(100, 50, 10, 0, 0, 0.01, 2000L, 1800L, "sess-1", 1, false);
        MessageDispatch dispatch = SpeechActMapper.mapToDispatch(
                CHANNEL_ID, inbound(MessageType.COMMAND), AGENT_ID, "LGTM", stats);

        assertThat(dispatch.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(dispatch.content()).isEqualTo("LGTM");
        assertThat(dispatch.sender()).isEqualTo(AGENT_ID);
        assertThat(dispatch.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(dispatch.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(dispatch.inReplyTo()).isNotNull();
    }

    @Test
    void mapToDispatch_queryCommitment_producesResponseOnly() {
        var stats = new AgentEvent.InvocationComplete(80, 40, 0, 0, 0, null, 1500L, 1200L, "sess-1", 1, false);
        MessageDispatch dispatch = SpeechActMapper.mapToDispatch(
                CHANNEL_ID, inbound(MessageType.QUERY), AGENT_ID, "The answer is 42", stats);

        assertThat(dispatch.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(dispatch.content()).isEqualTo("The answer is 42");
    }

    @Test
    void mapToolStatus_producesStatusMessage() {
        var tool = new AgentEvent.ToolCallComplete(0, "call-1", "grep", "{\"pattern\":\"TODO\"}");
        MessageDispatch dispatch = SpeechActMapper.mapToolStatus(
                CHANNEL_ID, inbound(MessageType.COMMAND), AGENT_ID, tool);

        assertThat(dispatch.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatch.sender()).isEqualTo(AGENT_ID);
        assertThat(dispatch.content()).contains("grep");
    }

    @Test
    void mapFailure_producesFailureMessage() {
        MessageDispatch dispatch = SpeechActMapper.mapFailure(
                CHANNEL_ID, inbound(MessageType.COMMAND), AGENT_ID,
                new RuntimeException("API timeout"));

        assertThat(dispatch.type()).isEqualTo(MessageType.FAILURE);
        assertThat(dispatch.content()).contains("API timeout");
        assertThat(dispatch.correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void mapToDispatch_passesInvocationContext() {
        String context = "[\"agent-a\"]";
        MessageDispatch dispatch = SpeechActMapper.mapToDispatch(
                CHANNEL_ID, inbound(MessageType.COMMAND), AGENT_ID, "response", null, context);
        assertThat(dispatch.invocationContext()).isEqualTo(context);
    }

    @Test
    void mapToDispatch_withoutContext_defaultsToNull() {
        MessageDispatch dispatch = SpeechActMapper.mapToDispatch(
                CHANNEL_ID, inbound(MessageType.COMMAND), AGENT_ID, "response", null);
        assertThat(dispatch.invocationContext()).isNull();
    }
}

package io.casehub.qhorus.agent.bridge;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;

import java.util.UUID;

public final class SpeechActMapper {

    private SpeechActMapper() {}

    public static MessageDispatch mapToDispatch(UUID channelId, OutboundMessage inbound,
                                                 String agentInstanceId, String responseText,
                                                 AgentEvent.InvocationComplete stats,
                                                 String invocationContext) {
        String telemetry = formatTelemetry(stats);
        return MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentInstanceId)
                .type(MessageType.RESPONSE)
                .content(responseText)
                .correlationId(inbound.correlationId())
                .inReplyTo(inbound.sequenceId())
                .actorType(ActorType.AGENT)
                .telemetry(telemetry)
                .invocationContext(invocationContext)
                .build();
    }

    public static MessageDispatch mapToDispatch(UUID channelId, OutboundMessage inbound,
                                                 String agentInstanceId, String responseText,
                                                 AgentEvent.InvocationComplete stats) {
        return mapToDispatch(channelId, inbound, agentInstanceId, responseText, stats, null);
    }

    public static MessageDispatch mapToolStatus(UUID channelId, OutboundMessage inbound,
                                                 String agentInstanceId,
                                                 AgentEvent.ToolCallComplete tool,
                                                 String invocationContext) {
        String content = "Tool: " + tool.name() + " (id=" + tool.id() + ")";
        return MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentInstanceId)
                .type(MessageType.STATUS)
                .content(content)
                .correlationId(inbound.correlationId())
                .actorType(ActorType.AGENT)
                .invocationContext(invocationContext)
                .build();
    }

    public static MessageDispatch mapToolStatus(UUID channelId, OutboundMessage inbound,
                                                 String agentInstanceId,
                                                 AgentEvent.ToolCallComplete tool) {
        return mapToolStatus(channelId, inbound, agentInstanceId, tool, null);
    }

    public static MessageDispatch mapFailure(UUID channelId, OutboundMessage inbound,
                                              String agentInstanceId, Throwable error,
                                              String invocationContext) {
        String content = "Agent invocation failed: " + error.getMessage();
        return MessageDispatch.builder()
                .channelId(channelId)
                .sender(agentInstanceId)
                .type(MessageType.FAILURE)
                .content(content)
                .correlationId(inbound.correlationId())
                .inReplyTo(inbound.sequenceId())
                .actorType(ActorType.AGENT)
                .invocationContext(invocationContext)
                .build();
    }

    public static MessageDispatch mapFailure(UUID channelId, OutboundMessage inbound,
                                              String agentInstanceId, Throwable error) {
        return mapFailure(channelId, inbound, agentInstanceId, error, null);
    }

    private static String formatTelemetry(AgentEvent.InvocationComplete stats) {
        if (stats == null) return null;
        return "{\"input_tokens\":" + stats.inputTokens()
                + ",\"output_tokens\":" + stats.outputTokens()
                + ",\"thinking_tokens\":" + stats.thinkingTokens()
                + ",\"duration_ms\":" + stats.durationMs()
                + (stats.totalCostUsd() != null ? ",\"total_cost_usd\":" + stats.totalCostUsd() : "")
                + "}";
    }
}

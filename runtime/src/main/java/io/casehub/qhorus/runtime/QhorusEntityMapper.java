package io.casehub.qhorus.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.message.Message;

@ApplicationScoped
public class QhorusEntityMapper {

    @Inject
    ObjectMapper mapper;

    QhorusEntityMapper() {}

    public QhorusEntityMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ChannelDetail toChannelDetail(Channel ch, long messageCount,
                                         Optional<ChannelConnectorBinding> binding) {
        ChannelDetail.ConnectorBinding detailBinding = binding
                .map(b -> new ChannelDetail.ConnectorBinding(
                        b.inboundConnectorId, b.externalKey,
                        b.outboundConnectorId, b.outboundDestination))
                .orElse(null);
        return new ChannelDetail(
                ch.id, ch.name, ch.description,
                ch.semantic != null ? ch.semantic.name() : null,
                ch.barrierContributors, messageCount,
                ch.lastActivityAt != null ? ch.lastActivityAt.toString() : null,
                ch.paused, ch.allowedWriters, ch.adminInstances,
                ch.rateLimitPerChannel, ch.rateLimitPerInstance, ch.allowedTypes,
                detailBinding);
    }

    public Map<String, Object> toTimelineEntry(Message m) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", m.id);
        if (m.messageType == MessageType.EVENT) {
            entry.put("type", "EVENT");
            entry.put("created_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("occurred_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("agent_id", m.sender);
            entry.put("message_type", null);
            String toolName = null;
            Long durationMs = null;
            Long tokenCount = null;
            if (m.content != null) {
                try {
                    JsonNode node = mapper.readTree(m.content);
                    JsonNode tn = node.get("tool_name");
                    if (tn != null && tn.isTextual()) toolName = tn.asText();
                    JsonNode dm = node.get("duration_ms");
                    if (dm != null && dm.isNumber()) durationMs = dm.asLong();
                    JsonNode tc = node.get("token_count");
                    if (tc != null && tc.isNumber()) tokenCount = tc.asLong();
                } catch (Exception ignored) {
                }
            }
            entry.put("tool_name", toolName);
            entry.put("duration_ms", durationMs);
            entry.put("token_count", tokenCount);
        } else {
            entry.put("type", "MESSAGE");
            entry.put("created_at", m.createdAt != null ? m.createdAt.toString() : null);
            entry.put("sender", m.sender);
            entry.put("message_type", m.messageType != null ? m.messageType.name().toLowerCase() : null);
            entry.put("content", m.content);
            entry.put("correlation_id", m.correlationId);
            entry.put("tool_name", null);
        }
        return entry;
    }
}

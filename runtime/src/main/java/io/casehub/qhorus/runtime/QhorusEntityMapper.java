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
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
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
                ch.rateLimitPerChannel, ch.rateLimitPerInstance, ch.allowedTypes, ch.deniedTypes,
                detailBinding);
    }

    /**
     * Maps a persisted {@code Message} entity to a {@link MessageView} read-side DTO.
     *
     * <p><strong>Field rename:</strong> {@code Message.messageType} → {@code MessageView.type}.
     * This is intentional — consistent with {@code DispatchResult.type}. Never read
     * {@code msg.messageType} and assign it directly to {@code MessageView.type} in
     * call sites; always go through this method.
     */
    public MessageView toMessageView(final Message msg) {
        return new MessageView(
                msg.id,
                msg.channelId,
                msg.sender,
                msg.messageType,   // field rename: entity uses messageType, DTO uses type
                msg.content,
                msg.correlationId,
                msg.inReplyTo,
                msg.target,
                msg.artefactRefs,
                msg.actorType,
                msg.createdAt,
                msg.deadline,
                msg.replyCount);
    }

    public Map<String, Object> toTimelineEntry(Message m) {
        return toTimelineEntry(m, null);
    }

    /**
     * Maps a {@link Message} to a timeline entry map.
     *
     * <p>For EVENT messages, telemetry fields ({@code tool_name}, {@code duration_ms},
     * {@code token_count}) are populated from the {@code ledgerEntry} when provided —
     * since EVENT messages carry no {@code content} (the guard in
     * {@code MessageDispatch.Builder.build()} enforces this), the telemetry lives only
     * in the {@link MessageLedgerEntry} columns written by {@code LedgerWriteService}.
     *
     * @param m          the persisted message entity
     * @param ledgerEntry the corresponding ledger entry (nullable); used for EVENT telemetry
     */
    public Map<String, Object> toTimelineEntry(Message m, MessageLedgerEntry ledgerEntry) {
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
            // Primary source: ledger entry columns (set when dispatched via MessageDispatch.telemetry())
            if (ledgerEntry != null) {
                toolName = ledgerEntry.toolName;
                durationMs = ledgerEntry.durationMs;
                tokenCount = ledgerEntry.tokenCount;
            }
            // Fallback: legacy content field (pre-#257 messages stored telemetry JSON in content)
            if (toolName == null && durationMs == null && tokenCount == null && m.content != null) {
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

package io.casehub.qhorus.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return toChannelDetail(ch, messageCount, binding, null);
    }

    public ChannelDetail toChannelDetail(Channel ch, long messageCount,
                                         Optional<ChannelConnectorBinding> binding,
                                         String spaceName) {
        ChannelDetail.ConnectorBinding detailBinding = binding
                                                               .map(b -> new ChannelDetail.ConnectorBinding(
                                                                       b.inboundConnectorId(), b.externalKey(),
                                                                       b.outboundConnectorId(), b.outboundDestination()))
                                                               .orElse(null);
        return new ChannelDetail(
                ch.id(), ch.name(), ch.description(),
                ch.semantic() != null ? ch.semantic().name() : null,
                joinCsv(ch.barrierContributors()), messageCount,
                ch.lastActivityAt() != null ? ch.lastActivityAt().toString() : null,
                ch.paused(), joinCsv(ch.allowedWriters()), joinCsv(ch.adminInstances()),
                ch.rateLimitPerChannel(), ch.rateLimitPerInstance(),
                ch.allowedTypes() != null ? MessageType.serializeTypes(ch.allowedTypes()) : null,
                ch.deniedTypes() != null ? MessageType.serializeTypes(ch.deniedTypes()) : null,
                ch.spaceId(),
                spaceName,
                joinCsv(ch.reviewerInstances()),
                joinCsv(ch.protocols()),
                joinCsv(ch.protocolParticipants()),
                io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(ch),
                detailBinding);}

    public MessageView toMessageView(final Message msg) {
        return new MessageView(
                msg.id(),
                msg.channelId(),
                msg.sender(),
                msg.messageType(),
                msg.content(),
                msg.correlationId(),
                msg.inReplyTo(),
                msg.target(),
                msg.topic(),
                msg.artefactRefs(),
                msg.actorType(),
                msg.createdAt(),
                msg.deadline(),
                msg.replyCount());}

    public Map<String, Object> toTimelineEntry(Message m) {
        return toTimelineEntry(m, null);
    }

    public Map<String, Object> toTimelineEntry(Message m, MessageLedgerEntry ledgerEntry) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", m.id());
        if (m.messageType() == MessageType.EVENT) {
            entry.put("type", "EVENT");
            entry.put("created_at", m.createdAt() != null ? m.createdAt().toString() : null);
            entry.put("occurred_at", m.createdAt() != null ? m.createdAt().toString() : null);
            entry.put("agent_id", m.sender());
            entry.put("message_type", null);
            String toolName   = null;
            Long   durationMs = null;
            Long   tokenCount = null;
            if (ledgerEntry != null) {
                toolName   = ledgerEntry.toolName;
                durationMs = ledgerEntry.durationMs;
                tokenCount = ledgerEntry.tokenCount;
            }
            if (toolName == null && durationMs == null && tokenCount == null && m.content() != null) {
                try {
                    JsonNode node = mapper.readTree(m.content());
                    JsonNode tn   = node.get("tool_name");
                    if (tn != null && tn.isTextual()) {toolName = tn.asText();}
                    JsonNode dm = node.get("duration_ms");
                    if (dm != null && dm.isNumber()) {durationMs = dm.asLong();}
                    JsonNode tc = node.get("token_count");
                    if (tc != null && tc.isNumber()) {tokenCount = tc.asLong();}
                } catch (Exception ignored) {
                }
            }
            entry.put("tool_name", toolName);
            entry.put("duration_ms", durationMs);
            entry.put("token_count", tokenCount);
        } else {
            entry.put("type", "MESSAGE");
            entry.put("created_at", m.createdAt() != null ? m.createdAt().toString() : null);
            entry.put("sender", m.sender());
            entry.put("message_type", m.messageType() != null ? m.messageType().name().toLowerCase() : null);
            entry.put("content", m.content());
            entry.put("correlation_id", m.correlationId());
            entry.put("in_reply_to", m.inReplyTo());
            entry.put("target", m.target());
            entry.put("reply_count", m.replyCount());
            entry.put("topic", m.topic());
            entry.put("deadline", m.deadline() != null ? m.deadline().toString() : null);
            entry.put("artefact_refs", m.artefactRefs() != null && !m.artefactRefs().isEmpty()
                                       ? m.artefactRefs() : null);
            entry.put("tool_name", null);
        }
        return entry;}

    private static String joinCsv(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(",", list);
    }

}

package io.casehub.qhorus.runtime.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.QhorusEntityMapper;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.watchdog.Watchdog;

public abstract class QhorusMcpToolsBase {

    @Inject
    QhorusEntityMapper entityMapper;

    public record RegisterResponse(
            String instanceId,
            List<ChannelSummary> activeChannels,
            List<InstanceInfo> onlineInstances) {
    }

    public record ChannelSummary(String name, String description, String semantic) {
    }

    public record MessageSummary(
            Long messageId,
            String sender,
            String messageType,
            String content,
            String correlationId,
            Long inReplyTo,
            String createdAt,
            List<String> artefactRefs,
            /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
            String target) {
    }

    public record CheckResult(
            List<MessageSummary> messages,
            Long lastId,
            /** Non-null on BARRIER channels that have not yet released — lists pending contributors. */
            String barrierStatus) {
    }

    public record ArtefactDetail(
            UUID artefactId,
            String key,
            String description,
            String createdBy,
            String content,
            boolean complete,
            long sizeBytes,
            String updatedAt) {
    }

    public record WaitResult(
            boolean found,
            boolean timedOut,
            String correlationId,
            /** The matching response message, or null on timeout. */
            MessageSummary message,
            String status) {
    }

    public record CancelWaitResult(
            String correlationId,
            boolean cancelled,
            String message) {
    }

    public record CommitmentDetail(
            String commitmentId,
            String correlationId,
            String channelId,
            String messageType,
            String requester,
            String obligor,
            String state,
            String expiresAt,
            String acknowledgedAt,
            String resolvedAt,
            String delegatedTo,
            String parentCommitmentId,
            String createdAt) {

        public static CommitmentDetail from(io.casehub.qhorus.runtime.message.Commitment c) {
            return new CommitmentDetail(
                    c.id != null ? c.id.toString() : null,
                    c.correlationId,
                    c.channelId != null ? c.channelId.toString() : null,
                    c.messageType != null ? c.messageType.name() : null,
                    c.requester,
                    c.obligor,
                    c.state != null ? c.state.name() : null,
                    c.expiresAt != null ? c.expiresAt.toString() : null,
                    c.acknowledgedAt != null ? c.acknowledgedAt.toString() : null,
                    c.resolvedAt != null ? c.resolvedAt.toString() : null,
                    c.delegatedTo,
                    c.parentCommitmentId != null ? c.parentCommitmentId.toString() : null,
                    c.createdAt != null ? c.createdAt.toString() : null);
        }
    }

    public record ForceReleaseResult(
            String channelName,
            String semantic,
            int messageCount,
            List<MessageSummary> messages) {
    }

    public record RevokeResult(
            String artefactId,
            String key,
            String createdBy,
            long sizeBytes,
            int claimsReleased,
            boolean revoked,
            String message) {
    }

    public record DeleteMessageResult(
            Long messageId,
            boolean deleted,
            String sender,
            String messageType,
            String contentPreview,
            String message) {
    }

    public record ClearChannelResult(
            String channelName,
            int messagesDeleted,
            boolean cleared) {
    }

    public record DeleteChannelResult(
            String channelName,
            long messagesDeleted,
            String status) {
    }

    public record DeregisterResult(
            String instanceId,
            boolean deregistered,
            String message) {
    }

    public record MessagePreview(
            Long messageId,
            String sender,
            String messageType,
            String contentPreview,
            String createdAt) {
    }

    public record WatchdogSummary(
            String id,
            String conditionType,
            String targetName,
            Integer thresholdSeconds,
            Integer thresholdCount,
            String notificationChannel,
            String createdBy,
            String createdAt,
            String lastFiredAt) {
    }

    public record DeleteWatchdogResult(
            String watchdogId,
            boolean deleted,
            String message) {
    }

    // ── Ledger query response records (Epic #110) ─────────────────────────────

    /**
     * Computed enrichment for an obligation identified by a {@code correlationId}.
     * Raw entries are available via {@code list_ledger_entries(correlation_id=X)}.
     */
    public record ObligationChainSummary(
            String correlationId,
            String initiator,
            String createdAt,
            String resolvedAt,
            Long elapsedSeconds,
            /** MessageType of the terminal entry: DONE, FAILURE, DECLINE, or HANDOFF. Null if open. */
            String resolution,
            List<String> participants,
            int handoffCount,
            /** Live CommitmentStore state. Null if no matching Commitment exists. */
            CommitmentDetail commitment) {
    }

    /** One entry in a causal chain returned by {@code get_causal_chain}. */
    public record CausalChainEntry(
            String entryId,
            String messageType,
            String actorId,
            String correlationId,
            String occurredAt,
            /** Null for the root entry. */
            String causedByEntryId) {
    }

    /** A COMMAND entry with no terminal sibling, older than the stale threshold. */
    public record StalledObligation(
            String correlationId,
            String actorId,
            String content,
            String occurredAt,
            long stalledForSeconds) {
    }

    /** Obligation outcome statistics for a channel. */
    public record ObligationStats(
            int totalCommands,
            int fulfilled,
            int failed,
            int declined,
            int delegated,
            int stillOpen,
            int stalled,
            double fulfillmentRate) {
    }

    /** Per-tool telemetry aggregation used inside {@link TelemetrySummary}. */
    public record ToolTelemetry(
            int count,
            long avgDurationMs,
            long totalTokens) {
    }

    /** Telemetry summary returned by {@code get_telemetry_summary}. */
    public record TelemetrySummary(
            int totalEvents,
            Map<String, ToolTelemetry> byTool,
            long totalTokens,
            long totalDurationMs) {
    }

    public record BackendInfo(String backendId, String backendType, String actorType) {}

    public record DeregisterBackendResult(String channelName, String backendId,
            boolean success, String message) {}

    public record RegisterBackendResult(String channelName, String backendId,
            String backendType, String message) {}

    public record ChannelDigest(
            String channelName,
            String semantic,
            boolean paused,
            long messageCount,
            Map<String, Integer> senderBreakdown,
            Map<String, Integer> typeBreakdown,
            int artefactRefCount,
            List<String> activeAgents,
            List<MessagePreview> recentMessages,
            String oldestMessageAt,
            String newestMessageAt) {
    }

    /**
     * Throws {@link IllegalStateException} if the channel has an {@code admin_instances} list
     * and {@code callerInstanceId} is not in it (or is null).
     */
    protected static void checkAdminAccess(Channel ch, String callerInstanceId, String toolName) {
        if (ch.adminInstances == null || ch.adminInstances.isBlank()) {
            return;
        }
        if (callerInstanceId == null || callerInstanceId.isBlank()) {
            throw new IllegalStateException(
                    "Channel '" + ch.name + "' requires a caller_instance_id for " + toolName
                            + " — it has an admin_instances list.");
        }
        for (String raw : ch.adminInstances.split(",")) {
            if (raw.strip().equals(callerInstanceId)) {
                return;
            }
        }
        throw new IllegalStateException(
                "Caller '" + callerInstanceId + "' is not permitted to invoke " + toolName
                        + " on channel '" + ch.name + "'. Not in admin_instances list.");
    }

    /**
     * Parses a nullable/blank UUID tool parameter. Returns null if the value is null or blank;
     * throws {@link IllegalArgumentException} with a descriptive message if malformed.
     */
    protected static UUID parseOptionalUuid(final String paramName, final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(paramName + " is not a valid UUID: " + value);
        }
    }

    /**
     * Returns true if the message is visible to the given reader.
     * {@code readerTagsSupplier} is invoked lazily — only if the target is a capability/role prefix.
     */
    protected static boolean isVisibleToReader(Message m, String readerInstanceId,
            Supplier<List<String>> readerTagsSupplier) {
        if (readerInstanceId == null || readerInstanceId.isBlank()) {
            return true;
        }
        if (m.messageType == MessageType.EVENT) {
            return true;
        }
        if (m.target == null) {
            return true;
        }
        if (m.target.equals("instance:" + readerInstanceId)) {
            return true;
        }
        if (m.target.startsWith("capability:") || m.target.startsWith("role:")) {
            return readerTagsSupplier.get().contains(m.target);
        }
        return false;
    }

    protected String toolError(Exception e) {
        return "Error: " + e.getMessage();
    }

    protected ArtefactDetail toArtefactDetail(SharedData d) {
        return new ArtefactDetail(d.id, d.key, d.description, d.createdBy,
                d.content, d.complete, d.sizeBytes, d.updatedAt.toString());
    }

    protected MessageSummary toMessageSummary(Message m) {
        List<String> refs = (m.artefactRefs != null && !m.artefactRefs.isBlank())
                ? List.of(m.artefactRefs.split(","))
                : List.of();
        return new MessageSummary(m.id, m.sender, m.messageType.name(), m.content,
                m.correlationId, m.inReplyTo, m.createdAt.toString(), refs, m.target);
    }

    protected ChannelDetail toChannelDetail(Channel ch, long messageCount) {
        return entityMapper.toChannelDetail(ch, messageCount);
    }

    protected WatchdogSummary toWatchdogSummary(Watchdog w) {
        return new WatchdogSummary(
                w.id.toString(),
                w.conditionType,
                w.targetName,
                w.thresholdSeconds,
                w.thresholdCount,
                w.notificationChannel,
                w.createdBy,
                w.createdAt != null ? w.createdAt.toString() : null,
                w.lastFiredAt != null ? w.lastFiredAt.toString() : null);
    }

    /** Variant of {@link #toLedgerEntryMap} that prepends the channel name. Used by get_obligation_activity. */
    protected Map<String, Object> toLedgerEntryMapWithChannel(final MessageLedgerEntry e,
            final String channelName) {
        final Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("channel", channelName);
        result.putAll(toLedgerEntryMap(e));
        return result;
    }

    protected Map<String, Object> toLedgerEntryMap(final MessageLedgerEntry e) {
        final Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entry_id", e.id != null ? e.id.toString() : null);
        m.put("sequence_number", (long) e.sequenceNumber);
        m.put("message_type", e.messageType);
        m.put("entry_type", e.entryType != null ? e.entryType.name() : null);
        m.put("actor_id", e.actorId);
        m.put("target", e.target);
        m.put("content", e.content);
        m.put("correlation_id", e.correlationId);
        m.put("commitment_id", e.commitmentId != null ? e.commitmentId.toString() : null);
        m.put("caused_by_entry_id", e.causedByEntryId != null ? e.causedByEntryId.toString() : null);
        m.put("occurred_at", e.occurredAt != null ? e.occurredAt.toString() : null);
        m.put("message_id", e.messageId);
        // Telemetry — only include keys when values are present (EVENT-only fields)
        if (e.toolName != null) {
            m.put("tool_name", e.toolName);
        }
        if (e.durationMs != null) {
            m.put("duration_ms", e.durationMs);
        }
        if (e.tokenCount != null) {
            m.put("token_count", e.tokenCount);
        }
        if (e.contextRefs != null) {
            m.put("context_refs", e.contextRefs);
        }
        if (e.sourceEntity != null) {
            m.put("source_entity", e.sourceEntity);
        }
        return m;
    }

    protected Map<String, Object> toTimelineEntry(Message m) {
        return entityMapper.toTimelineEntry(m);
    }
}

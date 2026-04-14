package io.quarkiverse.qhorus.runtime.mcp;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.instance.Capability;
import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;

@ApplicationScoped
public class QhorusMcpTools {

    @Inject
    InstanceService instanceService;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    io.quarkiverse.qhorus.runtime.data.DataService dataService;

    // ---------------------------------------------------------------------------
    // Return-type records — public so tests can reference them
    // ---------------------------------------------------------------------------

    public record RegisterResponse(
            String instanceId,
            List<ChannelSummary> activeChannels,
            List<InstanceInfo> onlineInstances) {
    }

    public record ChannelSummary(String name, String description, String semantic) {
    }

    public record InstanceInfo(
            String instanceId,
            String description,
            String status,
            List<String> capabilities,
            String lastSeen) {
    }

    public record ChannelDetail(
            UUID channelId,
            String name,
            String description,
            String semantic,
            String barrierContributors,
            long messageCount,
            String lastActivityAt) {
    }

    public record MessageResult(
            Long messageId,
            String channelName,
            String sender,
            String messageType,
            String correlationId,
            Long inReplyTo,
            int parentReplyCount) {
    }

    public record MessageSummary(
            Long messageId,
            String sender,
            String messageType,
            String content,
            String correlationId,
            Long inReplyTo,
            String createdAt) {
    }

    public record CheckResult(
            List<MessageSummary> messages,
            Long lastId,
            /** Non-null on BARRIER channels that have not yet released — lists pending contributors. */
            String barrierStatus) {
    }

    public record ArtefactDetail(
            java.util.UUID artefactId,
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

    // ---------------------------------------------------------------------------
    // Instance management tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register", description = "Register an agent instance with capability tags. "
            + "Returns active channels and online instances as immediate context.")
    @Transactional
    public RegisterResponse register(
            @ToolArg(name = "instance_id", description = "Unique human-readable identifier for this agent") String instanceId,
            @ToolArg(name = "description", description = "Description of this agent's role") String description,
            @ToolArg(name = "capabilities", description = "Capability tags for peer discovery", required = false) List<String> capabilities,
            @ToolArg(name = "claudony_session_id", description = "Optional Claudony session ID for managed workers", required = false) String claudonySessionId) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        Instance instance = instanceService.register(instanceId, description, caps, claudonySessionId);

        List<ChannelSummary> channels = channelService.listAll().stream()
                .map(ch -> new ChannelSummary(ch.name, ch.description, ch.semantic.name()))
                .toList();

        List<InstanceInfo> onlineInstances = buildInstanceInfoList(instanceService.listAll());

        return new RegisterResponse(instance.instanceId, channels, onlineInstances);
    }

    @Tool(name = "list_instances", description = "List registered agent instances. "
            + "Optionally filter by capability tag.")
    public List<InstanceInfo> listInstances(
            @ToolArg(name = "capability", description = "Filter by capability tag (optional)", required = false) String capability) {
        List<Instance> instances = (capability != null && !capability.isBlank())
                ? instanceService.findByCapability(capability)
                : instanceService.listAll();
        return buildInstanceInfoList(instances);
    }

    // ---------------------------------------------------------------------------
    // Channel management tools
    // ---------------------------------------------------------------------------

    @Tool(name = "create_channel", description = "Create a named channel with declared semantic. "
            + "Semantic defaults to APPEND if not specified.")
    @Transactional
    public ChannelDetail createChannel(
            @ToolArg(name = "name", description = "Unique channel name") String name,
            @ToolArg(name = "description", description = "Channel purpose description") String description,
            @ToolArg(name = "semantic", description = "Channel semantic: APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE", required = false) String semantic,
            @ToolArg(name = "barrier_contributors", description = "Comma-separated contributor names (BARRIER channels only)", required = false) String barrierContributors) {
        ChannelSemantic sem;
        if (semantic == null || semantic.isBlank()) {
            sem = ChannelSemantic.APPEND;
        } else {
            try {
                sem = ChannelSemantic.valueOf(semantic.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid semantic '" + semantic + "'. Valid values: APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE");
            }
        }
        Channel ch = channelService.create(name, description, sem, barrierContributors);
        return toChannelDetail(ch, 0L);
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public List<ChannelDetail> listChannels() {
        List<Channel> channels = channelService.listAll();
        if (channels.isEmpty()) {
            return List.of();
        }
        // Batch all message counts in one GROUP BY query — avoids N+1
        @SuppressWarnings("unchecked")
        List<Object[]> countRows = Message.getEntityManager()
                .createQuery("SELECT m.channelId, COUNT(m) FROM Message m GROUP BY m.channelId")
                .getResultList();
        Map<UUID, Long> countByChannel = countRows.stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
        return channels.stream()
                .map(ch -> toChannelDetail(ch, countByChannel.getOrDefault(ch.id, 0L)))
                .toList();
    }

    @Tool(name = "find_channel", description = "Search channels by keyword in name or description.")
    public List<ChannelDetail> findChannel(
            @ToolArg(name = "keyword", description = "Search term (case-insensitive)") String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";
        List<Channel> matches = Channel.<Channel> find(
                "LOWER(name) LIKE ?1 OR LOWER(description) LIKE ?1", pattern).list();
        return matches.stream()
                .map(ch -> toChannelDetail(ch, Message.<Message> count("channelId", ch.id)))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Messaging tools
    // ---------------------------------------------------------------------------

    @Tool(name = "send_message", description = "Post a typed message to a channel. "
            + "For 'request' type, correlation_id is auto-generated if not supplied.")
    @Transactional
    public MessageResult sendMessage(
            @ToolArg(name = "channel_name", description = "Target channel name") String channelName,
            @ToolArg(name = "sender", description = "Sender identifier") String sender,
            @ToolArg(name = "type", description = "Message type: request, response, status, handoff, done, event") String type,
            @ToolArg(name = "content", description = "Message content") String content,
            @ToolArg(name = "correlation_id", description = "Correlation ID (auto-generated for request if omitted)", required = false) String correlationId,
            @ToolArg(name = "in_reply_to", description = "ID of the message being replied to", required = false) Long inReplyTo) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        MessageType msgType = MessageType.valueOf(type.toUpperCase());
        String corrId = correlationId;
        if (corrId == null && msgType == MessageType.REQUEST) {
            corrId = java.util.UUID.randomUUID().toString();
        }

        // LAST_WRITE enforcement: one authoritative writer per channel
        if (ch.semantic == ChannelSemantic.LAST_WRITE) {
            List<Message> existing = Message.<Message> find(
                    "channelId = ?1 ORDER BY id DESC", ch.id).page(0, 1).list();
            if (!existing.isEmpty()) {
                Message last = existing.get(0);
                if (last.sender.equals(sender)) {
                    // Same sender — overwrite in place, do not insert a new row.
                    // Replace the full message payload so the persisted record reflects the new write.
                    last.content = content;
                    last.messageType = msgType;
                    last.correlationId = corrId;
                    last.inReplyTo = inReplyTo;
                    last.createdAt = Instant.now();
                    channelService.updateLastActivity(ch.id);
                    return new MessageResult(last.id, ch.name, last.sender,
                            last.messageType.name(), last.correlationId, last.inReplyTo, 0);
                } else {
                    throw new IllegalStateException(
                            "LAST_WRITE channel '" + ch.name + "' already has a message from '"
                                    + last.sender + "'. Only the current writer may update this channel.");
                }
            }
        }

        Message msg = messageService.send(ch.id, sender, msgType, content, corrId, inReplyTo);

        int parentReplyCount = 0;
        if (inReplyTo != null) {
            parentReplyCount = messageService.findById(inReplyTo)
                    .map(m -> m.replyCount).orElse(0);
        }

        return new MessageResult(msg.id, ch.name, msg.sender, msg.messageType.name(),
                msg.correlationId, msg.inReplyTo, parentReplyCount);
    }

    @Tool(name = "check_messages", description = "Poll for messages on a channel after a given cursor ID. "
            + "Excludes EVENT type. Returns messages and last_id for subsequent polling. "
            + "Behaviour varies by channel semantic: EPHEMERAL deletes on read, "
            + "COLLECT delivers all and clears, BARRIER blocks until all contributors have written.")
    @Transactional
    public CheckResult checkMessages(
            @ToolArg(name = "channel_name", description = "Channel to poll") String channelName,
            @ToolArg(name = "after_id", description = "Return messages with ID > after_id (use 0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 20)", required = false) Integer limit,
            @ToolArg(name = "sender", description = "Filter by sender (optional)", required = false) String sender) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = limit != null ? limit : 20;

        return switch (ch.semantic) {
            case EPHEMERAL -> checkMessagesEphemeral(ch, cursor, pageSize);
            case COLLECT -> checkMessagesCollect(ch);
            case BARRIER -> checkMessagesBarrier(ch);
            default -> checkMessagesAppend(ch, cursor, pageSize, sender);
        };
    }

    /** EPHEMERAL: deliver messages to first reader then delete them atomically. */
    private CheckResult checkMessagesEphemeral(Channel ch, long cursor, int pageSize) {
        List<Message> messages = messageService.pollAfter(ch.id, cursor, pageSize);
        if (!messages.isEmpty()) {
            List<Long> ids = messages.stream().map(m -> m.id).toList();
            Message.delete("channelId = ?1 AND id IN ?2", ch.id, ids);
        }
        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** COLLECT: deliver ALL accumulated messages atomically and clear the channel. */
    private CheckResult checkMessagesCollect(Channel ch) {
        List<Message> messages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();
        if (!messages.isEmpty()) {
            Message.delete("channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);
        }
        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** BARRIER: block until all declared contributors have written; then deliver and reset. */
    private CheckResult checkMessagesBarrier(Channel ch) {
        Set<String> required = Arrays.stream(
                ch.barrierContributors != null ? ch.barrierContributors.split(",") : new String[0])
                .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());

        // A BARRIER channel with no declared contributors is a configuration error.
        // Return a permanent-blocking status rather than silently releasing on every poll.
        if (required.isEmpty()) {
            return new CheckResult(List.of(), 0L, "Waiting for: (no contributors declared — check channel configuration)");
        }

        // Which contributors have written (non-EVENT messages only)
        @SuppressWarnings("unchecked")
        List<String> written = Message.getEntityManager()
                .createQuery("SELECT DISTINCT m.sender FROM Message m "
                        + "WHERE m.channelId = ?1 AND m.messageType != ?2")
                .setParameter(1, ch.id)
                .setParameter(2, MessageType.EVENT)
                .getResultList();

        Set<String> pending = required.stream()
                .filter(r -> !written.contains(r))
                .collect(Collectors.toSet());

        if (!pending.isEmpty()) {
            String status = "Waiting for: " + String.join(", ", pending.stream().sorted().toList());
            return new CheckResult(List.of(), 0L, status);
        }

        // All contributors have written — deliver and clear
        List<Message> messages = Message.<Message> find(
                "channelId = ?1 AND messageType != ?2 ORDER BY id ASC",
                ch.id, MessageType.EVENT).list();
        Message.delete("channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);
        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    /** APPEND / LAST_WRITE: standard cursor-based polling. */
    private CheckResult checkMessagesAppend(Channel ch, long cursor, int pageSize, String sender) {
        List<Message> messages = (sender != null && !sender.isBlank())
                ? messageService.pollAfterBySender(ch.id, cursor, pageSize, sender)
                : messageService.pollAfter(ch.id, cursor, pageSize);
        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    @Tool(name = "get_replies", description = "Retrieve all direct replies to a specific message.")
    public List<MessageSummary> getReplies(
            @ToolArg(name = "message_id", description = "ID of the parent message") Long messageId) {
        return Message.<Message> find("inReplyTo = ?1 ORDER BY id ASC", messageId)
                .list()
                .stream()
                .map(this::toMessageSummary)
                .toList();
    }

    @Tool(name = "search_messages", description = "Full-text keyword search across messages. Excludes EVENT type.")
    public List<MessageSummary> searchMessages(
            @ToolArg(name = "query", description = "Keyword to search for (case-insensitive)") String query,
            @ToolArg(name = "channel_name", description = "Restrict search to a specific channel (optional)", required = false) String channelName,
            @ToolArg(name = "limit", description = "Maximum results (default 20)", required = false) Integer limit) {
        String pattern = "%" + query.toLowerCase() + "%";
        int pageSize = limit != null ? limit : 20;

        List<Message> results;
        if (channelName != null && !channelName.isBlank()) {
            Channel ch = channelService.findByName(channelName)
                    .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
            results = Message.<Message> find(
                    "channelId = ?1 AND LOWER(content) LIKE ?2 AND messageType != ?3 ORDER BY id ASC",
                    ch.id, pattern, MessageType.EVENT).page(0, pageSize).list();
        } else {
            results = Message.<Message> find(
                    "LOWER(content) LIKE ?1 AND messageType != ?2 ORDER BY id ASC",
                    pattern, MessageType.EVENT).page(0, pageSize).list();
        }

        return results.stream().map(this::toMessageSummary).toList();
    }

    // ---------------------------------------------------------------------------
    // Correlation / wait_for_reply
    // ---------------------------------------------------------------------------

    @Tool(name = "wait_for_reply", description = "Block until a RESPONSE message with the given correlation_id "
            + "arrives on the channel, or until timeout_s seconds elapse. "
            + "Returns immediately if a matching response already exists.")
    public WaitResult waitForReply(
            @ToolArg(name = "channel_name", description = "Channel to watch for the response") String channelName,
            @ToolArg(name = "correlation_id", description = "UUID matching the correlation_id on the expected RESPONSE") String correlationId,
            @ToolArg(name = "timeout_s", description = "Seconds to wait before timing out (default 90)", required = false) Integer timeoutS,
            @ToolArg(name = "instance_id", description = "Waiting agent's instance ID for tracking (optional)", required = false) String instanceId) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int timeout = timeoutS != null ? timeoutS : 90;
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(timeout);
        // instance_id is the human-readable agent name (e.g. "alice"), not a UUID.
        // Look up its UUID; fall back to null if not registered — instance_id is metadata only.
        UUID instanceUuid = (instanceId != null && !instanceId.isBlank())
                ? instanceService.findByInstanceId(instanceId).map(i -> i.id).orElse(null)
                : null;

        // Register the pending reply (upserts if already present)
        messageService.registerPendingReply(correlationId, ch.id, instanceUuid, expiresAt);

        // Poll loop — each check is its own short transaction so we don't hold a connection
        long pollMs = 100;
        while (java.time.Instant.now().isBefore(expiresAt)) {
            Message response = messageService.findResponseByCorrelationId(ch.id, correlationId)
                    .orElse(null);
            if (response != null) {
                messageService.deletePendingReply(correlationId);
                return new WaitResult(true, false, correlationId, toMessageSummary(response),
                        "Response received for correlation_id=" + correlationId);
            }
            try {
                Thread.sleep(pollMs);
                pollMs = Math.min(pollMs * 2, 500); // backoff up to 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Timeout
        messageService.deletePendingReply(correlationId);
        return new WaitResult(false, true, correlationId, null,
                "Timed out after " + timeout + "s waiting for response to correlation_id=" + correlationId);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private List<InstanceInfo> buildInstanceInfoList(List<Instance> instances) {
        if (instances.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = instances.stream().map(i -> i.id).toList();
        Map<UUID, List<String>> capsByInstanceId = Capability
                .<Capability> find("instanceId IN ?1", ids)
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.instanceId,
                        Collectors.mapping(c -> c.tag, Collectors.toList())));

        return instances.stream()
                .map(i -> new InstanceInfo(
                        i.instanceId,
                        i.description,
                        i.status,
                        capsByInstanceId.getOrDefault(i.id, List.of()),
                        i.lastSeen.toString()))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Shared data tools
    // ---------------------------------------------------------------------------

    @Tool(name = "share_data", description = "Store a large artefact by key. "
            + "Supports chunked upload via append=true; last_chunk=true marks the artefact complete. "
            + "Returns the artefact UUID for use in message artefact_refs.")
    @Transactional
    public ArtefactDetail shareData(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "Content to store or append") String content,
            @ToolArg(name = "append", description = "Append to existing content (default false)", required = false) Boolean append,
            @ToolArg(name = "last_chunk", description = "Mark artefact complete (default true)", required = false) Boolean lastChunk) {
        boolean doAppend = append != null && append;
        boolean isLastChunk = lastChunk == null || lastChunk;
        var data = dataService.store(key, description, createdBy, content, doAppend, isLastChunk);
        return toArtefactDetail(data);
    }

    @Tool(name = "get_shared_data", description = "Retrieve a shared artefact by key or UUID. Exactly one of key or id must be provided.")
    public ArtefactDetail getSharedData(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        var data = hasKey
                ? dataService.getByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: key=" + key))
                : dataService.getByUuid(java.util.UUID.fromString(id))
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: id=" + id));
        return toArtefactDetail(data);
    }

    @Tool(name = "list_shared_data", description = "List all artefacts with metadata.")
    public List<ArtefactDetail> listSharedData() {
        return dataService.listAll().stream().map(this::toArtefactDetail).toList();
    }

    @Tool(name = "claim_artefact", description = "Declare this instance holds a reference to an artefact. Prevents GC.")
    @Transactional
    public String claimArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Claiming instance UUID") String instanceId) {
        dataService.claim(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
        return "claimed";
    }

    @Tool(name = "release_artefact", description = "Release a reference to an artefact. GC-eligible when all claims released.")
    @Transactional
    public String releaseArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Releasing instance UUID") String instanceId) {
        dataService.release(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
        return "released";
    }

    /** Not a @Tool — helper for tests and internal GC logic. */
    public boolean isGcEligible(String artefactId) {
        return dataService.isGcEligible(java.util.UUID.fromString(artefactId));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private ArtefactDetail toArtefactDetail(io.quarkiverse.qhorus.runtime.data.SharedData d) {
        return new ArtefactDetail(d.id, d.key, d.description, d.createdBy,
                d.content, d.complete, d.sizeBytes, d.updatedAt.toString());
    }

    private MessageSummary toMessageSummary(Message m) {
        return new MessageSummary(m.id, m.sender, m.messageType.name(), m.content,
                m.correlationId, m.inReplyTo, m.createdAt.toString());
    }

    private ChannelDetail toChannelDetail(Channel ch, long messageCount) {
        return new ChannelDetail(
                ch.id,
                ch.name,
                ch.description,
                ch.semantic.name(),
                ch.barrierContributors,
                messageCount,
                ch.lastActivityAt.toString());
    }
}

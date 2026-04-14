package io.quarkiverse.qhorus.runtime.mcp;

import java.util.List;
import java.util.Map;
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
            Long lastId) {
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
        Instance instance = instanceService.register(instanceId, description, caps);

        if (claudonySessionId != null) {
            instance.claudonySessionId = claudonySessionId;
        }

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
        ChannelSemantic sem = (semantic != null && !semantic.isBlank())
                ? ChannelSemantic.valueOf(semantic.toUpperCase())
                : ChannelSemantic.APPEND;
        Channel ch = channelService.create(name, description, sem, barrierContributors);
        return toChannelDetail(ch, 0L);
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public List<ChannelDetail> listChannels() {
        return channelService.listAll().stream()
                .map(ch -> toChannelDetail(ch, Message.<Message> count("channelId", ch.id)))
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
            + "Excludes EVENT type. Returns messages and last_id for subsequent polling.")
    public CheckResult checkMessages(
            @ToolArg(name = "channel_name", description = "Channel to poll") String channelName,
            @ToolArg(name = "after_id", description = "Return messages with ID > after_id (use 0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 20)", required = false) Integer limit,
            @ToolArg(name = "sender", description = "Filter by sender (optional)", required = false) String sender) {
        Channel ch = channelService.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = limit != null ? limit : 20;

        List<Message> messages = messageService.pollAfter(ch.id, cursor, pageSize);

        if (sender != null && !sender.isBlank()) {
            messages = messages.stream()
                    .filter(m -> sender.equals(m.sender))
                    .toList();
        }

        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.get(summaries.size() - 1).messageId();
        return new CheckResult(summaries, lastId);
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

    @Tool(name = "get_shared_data", description = "Retrieve a shared artefact by key or UUID.")
    public ArtefactDetail getSharedData(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        var data = (key != null && !key.isBlank())
                ? dataService.getByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: " + key))
                : dataService.getByUuid(java.util.UUID.fromString(id))
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: " + id));
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

package io.casehub.qhorus.mesh;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class MeshMcpTools {

    @Inject InstanceService instanceService;
    @Inject ChannelService channelService;
    @Inject MessageDispatcher messageDispatcher;
    @Inject MessageStore messageStore;
    @Inject InstanceStore instanceStore;
    @Inject ObjectMapper objectMapper;

    @Tool(description = "Register this session with the mesh relay. Returns registration confirmation.")
    public String mesh_register(
            @ToolArg(description = "Instance ID — typically the repo path relative to ~/claude/") String instance_id,
            @ToolArg(description = "Human-readable description of this session") String description,
            @ToolArg(description = "JSON object of key-value metadata, e.g. {\"project\":\"qhorus\",\"family\":\"casehub\"}") String metadata_json) {
        Map<String, String> metadata = parseMetadata(metadata_json);
        List<String> caps = metadata != null
                ? metadata.values().stream().toList()
                : List.of();
        Instance inst = instanceService.register(instance_id, description, caps, null, false, metadata);
        return "Registered: " + inst.instanceId() + " (id=" + inst.id() + ")";
    }

    @Tool(description = "Deregister this session from the mesh relay.")
    public String mesh_deregister(
            @ToolArg(description = "Instance ID to deregister") String instance_id) {
        instanceService.deregister(instance_id);
        return "Deregistered: " + instance_id;
    }

    @Tool(description = "Send a message to a channel.")
    public String mesh_send_message(
            @ToolArg(description = "Channel name") String channel,
            @ToolArg(description = "Sender instance ID") String sender,
            @ToolArg(description = "Message type: query, command, response, status, done, failure, propose, event") String type,
            @ToolArg(description = "Message content") String content) {
        Channel ch = channelService.findByName(channel)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channel));
        MessageType msgType = MessageType.valueOf(type.toUpperCase());
        DispatchResult result = messageDispatcher.dispatch(
                MessageDispatch.builder()
                        .channelId(ch.id())
                        .sender(sender)
                        .type(msgType)
                        .content(content)
                        .actorType(ActorType.AGENT)
                        .build());
        return "Sent " + msgType + " to " + channel + " (id=" + result.messageId() + ")";
    }

    @Tool(description = "Check messages in a channel. Returns recent messages.")
    public String mesh_check_messages(
            @ToolArg(description = "Channel name") String channel,
            @ToolArg(description = "Only return messages after this ID (optional)") Long after_id) {
        Channel ch = channelService.findByName(channel)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channel));
        MessageQuery.Builder qb = MessageQuery.builder().channelId(ch.id());
        if (after_id != null) {
            qb.afterId(after_id);
        }
        qb.limit(50);
        List<Message> messages = messageStore.scan(qb.build());
        if (messages.isEmpty()) {
            return "No messages in " + channel;
        }
        return messages.stream()
                .map(m -> "[" + m.id() + "] [" + m.messageType() + " from " + m.sender() + "] " + m.content())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Create a new channel with optional metadata.")
    public String mesh_create_channel(
            @ToolArg(description = "Channel name (slug format: lowercase, hyphens, slashes)") String name,
            @ToolArg(description = "JSON object of key-value metadata (optional)") String metadata_json) {
        Map<String, String> metadata = parseMetadata(metadata_json);
        Channel ch = channelService.create(
                ChannelCreateRequest.builder(name).metadata(metadata).build());
        return "Created channel: " + ch.name() + " (id=" + ch.id() + ")";
    }

    @Tool(description = "List channels, optionally filtered by metadata key-value pair.")
    public String mesh_list_channels(
            @ToolArg(description = "Metadata key to filter by (optional)") String metadata_key,
            @ToolArg(description = "Metadata value to filter by (optional)") String metadata_value) {
        List<Channel> channels;
        if (metadata_key != null && !metadata_key.isBlank()
                && metadata_value != null && !metadata_value.isBlank()) {
            channels = channelService.scan(ChannelQuery.byMetadata(metadata_key, metadata_value));
        } else {
            channels = channelService.scan(ChannelQuery.all());
        }
        if (channels.isEmpty()) {
            return "No channels found";
        }
        return channels.stream()
                .map(ch -> ch.name() + (ch.metadata() != null ? " " + ch.metadata() : ""))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Discover peer sessions by metadata key-value pair.")
    public String mesh_discover_peers(
            @ToolArg(description = "Metadata key to filter by") String metadata_key,
            @ToolArg(description = "Metadata value to filter by") String metadata_value) {
        List<Instance> peers = instanceStore.scan(InstanceQuery.byMetadata(metadata_key, metadata_value));
        if (peers.isEmpty()) {
            return "No peers found matching " + metadata_key + "=" + metadata_value;
        }
        return peers.stream()
                .map(i -> i.instanceId() + " — " + i.description()
                        + (i.metadata() != null ? " " + i.metadata() : ""))
                .collect(Collectors.joining("\n"));
    }

    private Map<String, String> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metadata JSON: " + e.getMessage());
        }
    }
}

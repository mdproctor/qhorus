package io.casehub.qhorus.runtime.dashboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkus.arc.properties.IfBuildProperty;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;


@IfBuildProperty(name = "quarkus.datasource.qhorus.reactive", stringValue = "true")
@ApplicationScoped
public class QhorusDashboardService {

    @Inject ReactiveChannelService channelService;
    @Inject ReactiveInstanceService instanceService;
    @Inject ReactiveMessageService messageService;
    @Inject ReactiveMessageStore messageStore;
    @Inject io.casehub.qhorus.runtime.QhorusEntityMapper entityMapper;

    // ── Response types ────────────────────────────────────────────────────────
    // Field names match QhorusMcpToolsBase DTO shapes for dashboard JS compat.
    // Migration to casehub-qhorus-api tracked in qhorus#175.

    public record ChannelView(
            UUID channelId, String name, String description, String semantic,
            String barrierContributors, long messageCount, String lastActivityAt,
            boolean paused, String allowedWriters, String adminInstances,
            Integer rateLimitPerChannel, Integer rateLimitPerInstance, String allowedTypes) {}

    public record InstanceView(
            String instanceId, String description, String status,
            List<String> capabilities, String lastSeen, boolean readOnly) {}

    public record HumanMessageResult(
            Long messageId, String channelName, String sender, String messageType,
            String correlationId, Long inReplyTo, int parentReplyCount,
            List<String> artefactRefs, String target) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<List<ChannelView>> listChannels() {
        return channelService.listAll().flatMap(channels -> {
            if (channels.isEmpty()) return Uni.createFrom().item(List.of());
            List<Uni<ChannelView>> unis = channels.stream()
                    .map(ch -> messageStore.countByChannel(ch.id)
                            .map(count -> toChannelView(ch, count)))
                    .toList();
            return Uni.join().all(unis).andFailFast();
        });
    }

    public Uni<List<InstanceView>> listInstances() {
        return instanceService.listAll().flatMap(instances -> {
            if (instances.isEmpty()) return Uni.createFrom().item(List.of());
            List<Uni<InstanceView>> unis = instances.stream()
                    .map(i -> instanceService.findCapabilityTagsForInstance(i.instanceId)
                            .map(tags -> new InstanceView(
                                    i.instanceId, i.description, i.status,
                                    tags, i.lastSeen.toString(), i.readOnly)))
                    .toList();
            return Uni.join().all(unis).andFailFast();
        });
    }

    /** Unknown channel returns empty list (consistent with existing MeshResource behavior). */
    public Uni<List<Map<String, Object>>> getTimeline(String channelName, Long afterId, int limit) {
        return channelService.findByName(channelName).flatMap(opt -> {
            if (opt.isEmpty()) return Uni.createFrom().item(List.of());
            int effectiveLimit = Math.min(Math.max(limit, 1), 200);
            return messageStore.scan(MessageQuery.poll(opt.get().id, afterId, effectiveLimit))
                    .map(msgs -> msgs.stream().map(entityMapper::toTimelineEntry).toList());
        });
    }

    public Uni<List<Map<String, Object>>> getFeed(int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 200);
        return channelService.listAll().flatMap(channels -> {
            if (channels.isEmpty()) return Uni.createFrom().item(List.of());
            Map<UUID, String> nameMap = channels.stream()
                    .collect(Collectors.toMap(ch -> ch.id, ch -> ch.name));
            return messageStore.scan(MessageQuery.recent(effectiveLimit))
                    .map(msgs -> msgs.stream()
                            .map(m -> {
                                Map<String, Object> entry =
                                        new HashMap<>(entityMapper.toTimelineEntry(m));
                                entry.put("channel", nameMap.getOrDefault(
                                        m.channelId, m.channelId.toString()));
                                return entry;
                            })
                            .toList());
        });
    }

    /**
     * Post a message from an authenticated human operator.
     * Checks channel existence ({@link IllegalArgumentException}) and paused state
     * ({@link IllegalStateException}). Human operators bypass agent-to-agent ACL and
     * rate limiting.
     */
    public Uni<HumanMessageResult> sendHumanMessage(
            String channelName, String sender, MessageType type, String content) {
        return channelService.findByName(channelName)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + channelName)))
                .invoke(ch -> {
                    if (ch.paused) throw new IllegalStateException(
                            "Channel '" + channelName + "' is paused");
                })
                .flatMap(ch -> messageService.send(
                        ch.id, sender, type, content, null, null, null, null, ActorType.HUMAN))
                .map(m -> new HumanMessageResult(
                        m.id, channelName, m.sender,
                        m.messageType != null ? m.messageType.name() : null,
                        m.correlationId, m.inReplyTo, 0, List.of(), m.target));
    }

    // ── Private mapping ───────────────────────────────────────────────────────

    private ChannelView toChannelView(Channel ch, int count) {
        return new ChannelView(
                ch.id, ch.name, ch.description,
                ch.semantic != null ? ch.semantic.name() : null,
                ch.barrierContributors, count,
                ch.lastActivityAt != null ? ch.lastActivityAt.toString() : null,
                ch.paused, ch.allowedWriters, ch.adminInstances,
                ch.rateLimitPerChannel, ch.rateLimitPerInstance, ch.allowedTypes);
    }

}

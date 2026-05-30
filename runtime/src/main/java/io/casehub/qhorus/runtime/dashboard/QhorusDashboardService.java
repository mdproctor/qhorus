package io.casehub.qhorus.runtime.dashboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkus.arc.properties.IfBuildProperty;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;


@IfBuildProperty(name = "quarkus.datasource.qhorus.reactive", stringValue = "true")
@ApplicationScoped
public class QhorusDashboardService {

    @Inject ReactiveChannelService channelService;
    @Inject ReactiveInstanceService instanceService;
    @Inject ReactiveMessageService messageService;
    @Inject ReactiveMessageStore messageStore;
    @Inject io.casehub.qhorus.runtime.QhorusEntityMapper entityMapper;
    @Inject ChannelBindingStore bindingStore;

    // ── Response types ────────────────────────────────────────────────────────
    // listChannels() returns ChannelDetail; listInstances() returns InstanceInfo —
    // both from casehub-qhorus-api (qhorus#201). HumanMessageResult has no api
    // equivalent yet and remains local until MessageResult is promoted (qhorus#175).

    public record HumanMessageResult(
            Long messageId, String channelName, String sender, String messageType,
            String correlationId, Long inReplyTo, int parentReplyCount,
            List<String> artefactRefs, String target) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<List<ChannelDetail>> listChannels() {
        return Uni.createFrom().item(bindingStore::findAll)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(bindings -> channelService.listAll().flatMap(channels -> {
                    if (channels.isEmpty()) return Uni.createFrom().item(List.of());
                    List<Uni<ChannelDetail>> unis = channels.stream()
                            .map(ch -> messageStore.countByChannel(ch.id)
                                    .map(count -> entityMapper.toChannelDetail(ch, count,
                                            Optional.ofNullable(bindings.get(ch.id)))))
                            .toList();
                    return Uni.join().all(unis).andFailFast();
                }));
    }

    public Uni<List<InstanceInfo>> listInstances() {
        return instanceService.listAll().flatMap(instances -> {
            if (instances.isEmpty()) return Uni.createFrom().item(List.of());
            List<Uni<InstanceInfo>> unis = instances.stream()
                    .map(i -> instanceService.findCapabilityTagsForInstance(i.instanceId)
                            .map(tags -> new InstanceInfo(
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
     *
     * <p>Throws {@link IllegalArgumentException} if the channel is not found.
     * Paused check is enforced inside {@link ReactiveMessageService#dispatch} —
     * it throws {@link IllegalStateException} when the channel is paused.
     *
     * <p><b>Design note (Refs #198):</b> This method fetches the channel by name to obtain
     * {@code ch.id}, and {@code dispatch()} fetches it a second time for the paused check.
     * This double-read is the known cost of the enforcement-gate pattern — the channel fetch
     * cannot be eliminated from either side without exposing implementation details across the
     * boundary. The cost is negligible at current scale. Full enforcement consolidation is
     * deferred to #193 (ReactiveMessageService enforcement parity).
     */
    public Uni<HumanMessageResult> sendHumanMessage(
            String channelName, String sender, MessageType type, String content) {
        return channelService.findByName(channelName)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + channelName)))
                .flatMap(ch -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(ch.id)
                                .sender(sender)
                                .type(type)
                                .content(content)
                                .actorType(ActorType.HUMAN)
                                .build()))
                .map(result -> new HumanMessageResult(
                        result.messageId(), channelName, result.sender(),
                        result.type() != null ? result.type().name() : null,
                        result.correlationId(), result.inReplyTo(),
                        result.parentReplyCount(),
                        result.artefactRefs().stream().map(UUID::toString).toList(),
                        result.target()));
    }

}


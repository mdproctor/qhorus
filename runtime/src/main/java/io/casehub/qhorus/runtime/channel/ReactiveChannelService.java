package io.casehub.qhorus.runtime.channel;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveChannelService {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    ReactiveChannelStore channelStore;

    @Inject
    public MessageStore messageStore;

    public Uni<Channel> create(io.casehub.qhorus.api.channel.ChannelCreateRequest req) {
        Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
        return Panache.withTransaction("qhorus", () -> channelStore.put(channel));
    }

    public Uni<Channel> setRateLimits(UUID channelId, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .rateLimitPerChannel(rateLimitPerChannel)
                        .rateLimitPerInstance(rateLimitPerInstance).build())));
    }

    public Uni<Channel> setAllowedWriters(UUID channelId, String allowedWriters) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .allowedWriters(Channel.splitCsv(allowedWriters)).build())));
    }

    public Uni<Channel> setAdminInstances(UUID channelId, String adminInstances) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .adminInstances(Channel.splitCsv(adminInstances)).build())));
    }

    public Uni<Channel> setTypeConstraints(final UUID channelId,
                                           final Set<MessageType> allowedTypes, final Set<MessageType> deniedTypes) {
        final Set<MessageType> allowed = allowedTypes != null ? allowedTypes : Set.of();
        final Set<MessageType> denied  = deniedTypes  != null ? deniedTypes  : Set.of();
        final Set<MessageType> overlap = new HashSet<>(allowed);
        overlap.retainAll(denied);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowed_types and denied_types must not overlap: " + overlap);
        }
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .allowedTypes(allowed.isEmpty() ? null : allowed)
                        .deniedTypes(denied.isEmpty() ? null : denied).build())));
    }

    public Uni<Optional<Channel>> findByName(String name) {
        return channelStore.findByName(name);
    }

    public Uni<List<Channel>> findByNamePrefix(String prefix) {
        return channelStore.scan(ChannelQuery.byNamePrefix(prefix));
    }

    public Uni<Optional<Channel>> findById(UUID id) {
        return channelStore.find(id);
    }

    public Uni<Channel> pause(UUID channelId) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder().paused(true).build())));
    }

    public Uni<Channel> resume(UUID channelId) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder().paused(false).build())));
    }

    public Uni<List<Channel>> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    public Uni<Long> delete(final UUID channelId, final boolean force) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    int messageCount = messageStore.countByChannel(ch.id());
                    if (messageCount > 0 && !force) {
                        throw new IllegalStateException(
                                "Channel '" + channelId + "' has " + messageCount
                                        + " messages. Pass force=true to delete anyway.");
                    }
                    if (messageCount > 0) {
                        messageStore.deleteAll(ch.id());
                    }
                    channelStore.delete(ch.id());
                    return (long) messageCount;
                }));
    }

    public Uni<Void> updateLastActivity(UUID channelId, String tenancyId) {
        return channelStore.updateLastActivity(channelId, tenancyId);
    }
}

package io.casehub.qhorus.runtime.channel;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
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

    /** Primary creation path — all named-param overloads funnel here via the 10-arg overload. */
    public Uni<Channel> create(ChannelCreateRequest req) {
        // populateChannel() is pure (no IO) — entity construction happens outside the transaction.
        // A JPA entity is a transient POJO until persist() is called inside the session;
        // it becomes managed when channelStore.put(channel) runs. Do NOT move this inside the lambda.
        Channel channel = populateChannel(req);
        return Panache.withTransaction("qhorus", () -> channelStore.put(channel));
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters, String adminInstances,
            Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        // ChannelCreateRequest construction runs validation before the transaction
        return create(new ChannelCreateRequest(
                name, description, semantic, barrierContributors,
                allowedWriters, adminInstances, rateLimitPerChannel, rateLimitPerInstance,
                null, null,
                null, null, null, null));
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors) {
        return create(name, description, semantic, barrierContributors, null, null, null, null);
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters) {
        return create(name, description, semantic, barrierContributors, allowedWriters, null, null, null);
    }

    public Uni<Channel> create(String name, String description, ChannelSemantic semantic,
            String barrierContributors, String allowedWriters, String adminInstances) {
        return create(name, description, semantic, barrierContributors, allowedWriters, adminInstances, null, null);
    }

    private Channel populateChannel(ChannelCreateRequest req) {
        Channel channel = new Channel();
        channel.name = req.name();
        channel.description = req.description();
        channel.semantic = req.semantic();
        channel.barrierContributors = req.barrierContributors();
        channel.allowedWriters = blankToNull(req.allowedWriters());
        channel.adminInstances = blankToNull(req.adminInstances());
        channel.rateLimitPerChannel = req.rateLimitPerChannel();
        channel.rateLimitPerInstance = req.rateLimitPerInstance();
        channel.allowedTypes = MessageType.serializeTypes(req.allowedTypes());
        channel.deniedTypes  = MessageType.serializeTypes(req.deniedTypes());
        channel.tenancyId = currentPrincipal.tenancyId();
        return channel;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public Uni<Channel> setRateLimits(UUID channelId, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    ch.rateLimitPerChannel = rateLimitPerChannel;
                    ch.rateLimitPerInstance = rateLimitPerInstance;
                    return ch;
                }));
    }

    public Uni<Channel> setAllowedWriters(UUID channelId, String allowedWriters) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    ch.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null
                            : allowedWriters;
                    return ch;
                }));
    }

    public Uni<Channel> setAdminInstances(UUID channelId, String adminInstances) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    ch.adminInstances = (adminInstances == null || adminInstances.isBlank()) ? null
                            : adminInstances;
                    return ch;
                }));
    }

    /**
     * Reactively replaces {@code allowedTypes} and {@code deniedTypes} on an existing channel.
     * Full-replacement: both fields are overwritten on every call. Constraint is prospective only.
     *
     * @throws IllegalArgumentException if a type name is unknown or the sets overlap
     */
    public Uni<Channel> setTypeConstraints(final UUID channelId,
            final Set<MessageType> allowedTypes, final Set<MessageType> deniedTypes) {
        // Validation runs synchronously before withTransaction — fires before the Vert.x thread switch
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
                .map(ch -> {
                    ch.allowedTypes = MessageType.serializeTypes(allowed);
                    ch.deniedTypes  = MessageType.serializeTypes(denied);
                    return ch;
                }));
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
                .map(ch -> {
                    ch.paused = true;
                    return ch;
                }));
    }

    public Uni<Channel> resume(UUID channelId) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    ch.paused = false;
                    return ch;
                }));
    }

    public Uni<List<Channel>> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    /**
     * Delete a channel by UUID. Uses blocking {@code MessageStore} for count and purge
     * (no reactive equivalents). Infrequent admin operation — blocking is acceptable.
     *
     * @param channelId the channel UUID
     * @param force when false, rejects if the channel has messages
     * @return number of messages deleted
     */
    public Uni<Long> delete(final UUID channelId, final boolean force) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(
                        () -> new IllegalArgumentException("Channel not found: " + channelId)))
                .map(ch -> {
                    int messageCount = messageStore.countByChannel(ch.id);
                    if (messageCount > 0 && !force) {
                        throw new IllegalStateException(
                                "Channel '" + channelId + "' has " + messageCount
                                        + " messages. Pass force=true to delete anyway.");
                    }
                    if (messageCount > 0) {
                        messageStore.deleteAll(ch.id);
                    }
                    channelStore.delete(ch.id);
                    return (long) messageCount;
                }));
    }

    public Uni<Void> updateLastActivity(UUID channelId, String tenancyId) {
        return channelStore.updateLastActivity(channelId, tenancyId);
    }
}

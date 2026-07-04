package io.casehub.qhorus.runtime.channel;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.persistence.PersistenceException;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.FindOrCreateResult;
import io.casehub.qhorus.api.channel.ReactiveChannelManager;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveChannelService implements ReactiveChannelManager {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    ReactiveChannelStore channelStore;

    @Inject
    public MessageStore messageStore;

    @Inject
    ChannelBindingStore channelBindingStore;

    @Inject
    ChannelGateway channelGateway;

    @Override
    public Uni<Channel> create(ChannelCreateRequest req) {
        Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
        return Panache.withTransaction("qhorus", () ->
                channelStore.put(channel)
                        .chain(ch -> Uni.createFrom().item(() -> {
                            if (req.hasConnectorBinding()) {
                                channelBindingStore.findByKey(
                                        req.inboundConnectorId(), req.externalKey())
                                        .ifPresent(existing -> {
                                            throw new IllegalStateException(
                                                    "Connector binding already exists for connector '"
                                                    + req.inboundConnectorId() + "' key '"
                                                    + req.externalKey() + "'");
                                        });
                                channelBindingStore.put(new ChannelConnectorBinding(
                                        ch.id(), req.inboundConnectorId(), req.externalKey(),
                                        req.outboundConnectorId(), req.outboundDestination()));
                            }
                            channelGateway.initChannel(ch.id(),
                                    new ChannelRef(ch.id(), ch.name()));
                            return ch;
                        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())));
    }

    @Override
    public Uni<FindOrCreateResult> findOrCreate(final ChannelCreateRequest req) {
        if (req.hasConnectorBinding()) {
            return findOrCreateWithBinding(req);
        }
        return findOrCreateByName(req);
    }

    private Uni<FindOrCreateResult> findOrCreateWithBinding(final ChannelCreateRequest req) {
        // Binding lookup is blocking — offload to worker pool
        return Uni.createFrom().item(() ->
                        channelBindingStore.findByKey(req.inboundConnectorId(), req.externalKey()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(existingBinding -> {
                    if (existingBinding.isPresent()) {
                        return channelStore.find(existingBinding.get().channelId())
                                .map(opt -> opt.orElseThrow(() -> new IllegalStateException(
                                        "Stale binding: binding exists for key '" + req.externalKey()
                                        + "' (connector=" + req.inboundConnectorId()
                                        + ") but referenced channel was deleted")))
                                .map(ch -> new FindOrCreateResult(ch, false));
                    }
                    // Create new channel with autoCreated flag
                    Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
                    Channel autoCreated = channel.toBuilder().autoCreated(true).build();
                    return Panache.withTransaction("qhorus", () ->
                            channelStore.put(autoCreated)
                                    .chain(saved -> Uni.createFrom().item(() -> {
                                        channelBindingStore.put(new ChannelConnectorBinding(
                                                saved.id(), req.inboundConnectorId(), req.externalKey(),
                                                req.outboundConnectorId(), req.outboundDestination()));
                                        channelGateway.initChannel(saved.id(),
                                                new ChannelRef(saved.id(), saved.name()));
                                        return new FindOrCreateResult(saved, true);
                                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())));
                });
    }

    private Uni<FindOrCreateResult> findOrCreateByName(final ChannelCreateRequest req) {
        return Panache.withTransaction("qhorus", () ->
                channelStore.findByName(req.name())
                        .flatMap(existing -> {
                            if (existing.isPresent()) {
                                return Uni.createFrom().item(new FindOrCreateResult(existing.get(), false));
                            }
                            return create(req).map(ch -> new FindOrCreateResult(ch, true));
                        }))
                .onFailure(PersistenceException.class).recoverWithUni(ex ->
                        Panache.withSession("qhorus", () ->
                                channelStore.findByName(req.name())
                                        .map(opt -> opt.map(ch -> new FindOrCreateResult(ch, false))
                                                .orElseThrow(() -> (RuntimeException) ex))));
    }

    @Override
    public Uni<Channel> setRateLimits(UUID channelId, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .rateLimitPerChannel(rateLimitPerChannel)
                        .rateLimitPerInstance(rateLimitPerInstance).build())));
    }

    @Override
    public Uni<Channel> setAllowedWriters(UUID channelId, List<String> allowedWriters) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .allowedWriters(allowedWriters).build())));
    }

    @Override
    public Uni<Channel> setAdminInstances(UUID channelId, List<String> adminInstances) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder()
                        .adminInstances(adminInstances).build())));
    }

    @Override
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

    @Override
    public Uni<Channel> pause(UUID channelId) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder().paused(true).build())));
    }

    @Override
    public Uni<Channel> resume(UUID channelId) {
        return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId)))
                .flatMap(ch -> channelStore.put(ch.toBuilder().paused(false).build())));
    }

    public Uni<List<Channel>> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    @Override
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

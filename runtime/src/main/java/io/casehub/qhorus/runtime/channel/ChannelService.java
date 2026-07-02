package io.casehub.qhorus.runtime.channel;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;

@ApplicationScoped
public class ChannelService {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    ChannelStore channelStore;

    @Inject
    MessageStore messageStore;

    @Inject
    ChannelBindingStore channelBindingStore;

    @Inject
    ChannelGateway channelGateway;

    @Transactional
    public Channel create(final ChannelCreateRequest req) {
        Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
        channel = channelStore.put(channel);

        if (req.hasConnectorBinding()) {
            channelBindingStore.findByKey(req.inboundConnectorId(), req.externalKey())
                    .ifPresent(existing -> {
                        throw new IllegalStateException(
                                "Connector binding already exists for connector '"
                                + req.inboundConnectorId() + "' key '" + req.externalKey() + "'");
                    });
            channelBindingStore.put(new ChannelConnectorBinding(
                    channel.id(), req.inboundConnectorId(), req.externalKey(),
                    req.outboundConnectorId(), req.outboundDestination()));
        }

        channelGateway.initChannel(channel.id(), new ChannelRef(channel.id(), channel.name()));

        return channel;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public FindOrCreateResult findOrCreateWithBinding(final ChannelCreateRequest req) {
        if (!req.hasConnectorBinding()) {
            throw new IllegalArgumentException("findOrCreateWithBinding requires a connector binding");
        }
        Optional<ChannelConnectorBinding> existingBinding = channelBindingStore
                .findByKey(req.inboundConnectorId(), req.externalKey());
        if (existingBinding.isPresent()) {
            Channel existing = channelStore.find(existingBinding.get().channelId())
                                           .orElseThrow(() -> new IllegalStateException(
                            "Stale binding: binding exists for key '" + req.externalKey()
                            + "' (connector=" + req.inboundConnectorId()
                            + ") but referenced channel was deleted"));
            return new FindOrCreateResult(existing, false);
        }

        Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
        channel = channel.toBuilder().autoCreated(true).build();
        channel = channelStore.put(channel);

        channelBindingStore.put(new ChannelConnectorBinding(
                channel.id(), req.inboundConnectorId(), req.externalKey(),
                req.outboundConnectorId(), req.outboundDestination()));

        return new FindOrCreateResult(channel, true);
    }

    @Transactional
    public Channel setRateLimits(UUID channelId, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .rateLimitPerChannel(rateLimitPerChannel)
                .rateLimitPerInstance(rateLimitPerInstance).build());
    }

    @Transactional
    public Channel setAllowedWriters(UUID channelId, String allowedWriters) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .allowedWriters(Channel.splitCsv(allowedWriters)).build());
    }

    @Transactional
    public Channel setAdminInstances(UUID channelId, String adminInstances) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .adminInstances(Channel.splitCsv(adminInstances)).build());
    }

    @Transactional
    public Channel setTypeConstraints(final UUID channelId,
                                      final Set<MessageType> allowedTypes, final Set<MessageType> deniedTypes) {
        final Set<MessageType> allowed = allowedTypes != null ? allowedTypes : Set.of();
        final Set<MessageType> denied  = deniedTypes  != null ? deniedTypes  : Set.of();
        final Set<MessageType> overlap = new HashSet<>(allowed);
        overlap.retainAll(denied);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowed_types and denied_types must not overlap: " + overlap);
        }
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .allowedTypes(allowed.isEmpty() ? null : allowed)
                .deniedTypes(denied.isEmpty() ? null : denied).build());
    }

    public Optional<Channel> findByName(String name) {
        return channelStore.findByName(name);
    }

    public List<Channel> findByNamePrefix(String prefix) {
        return channelStore.scan(ChannelQuery.byNamePrefix(prefix));
    }

    public Optional<Channel> findById(UUID id) {
        return channelStore.find(id);
    }

    public Optional<Channel> findByConnectorKey(String connectorId, String externalKey) {
        return channelBindingStore.findByKey(connectorId, externalKey)
                .flatMap(binding -> channelStore.find(binding.channelId()));
    }

    @Transactional
    public Channel pause(UUID channelId) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().paused(true).build());
    }

    @Transactional
    public Channel resume(UUID channelId) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().paused(false).build());
    }

    public List<Channel> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    @Transactional
    public long delete(final UUID channelId, final boolean force) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
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
        return messageCount;
    }

    @Transactional
    public ChannelConnectorBinding updateConnectorBinding(UUID channelId,
            String outboundConnectorId, String outboundDestination) {
        Channel channel = channelStore.find(channelId)
                                      .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        ChannelConnectorBinding binding = channelBindingStore.findByChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "No connector binding for channel: " + channelId));
        ChannelConnectorBinding updated = new ChannelConnectorBinding(
                binding.channelId(), binding.inboundConnectorId(), binding.externalKey(),
                outboundConnectorId, outboundDestination);
        channelBindingStore.put(updated);
        channelGateway.initChannel(channel.id(), new ChannelRef(channel.id(), channel.name()));
        return updated;
    }

    @Transactional
    public void updateLastActivity(UUID channelId, String tenancyId) {
        channelStore.updateLastActivity(channelId, tenancyId);
    }
}

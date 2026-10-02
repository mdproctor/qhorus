package io.casehub.qhorus.runtime.channel;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelManager;
import io.casehub.qhorus.api.channel.ChannelReader;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.FindOrCreateResult;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ChannelService implements ChannelManager, ChannelReader {

    CurrentPrincipal currentPrincipal;
    ChannelStore channelStore;
    MessageStore messageStore;
    io.casehub.qhorus.api.store.ChannelMembershipStore membershipStore;
    ChannelBindingStore channelBindingStore;
    ChannelGateway channelGateway;
    ChannelCreateHelper channelCreateHelper;


    ChannelService() {}

    public ChannelService(CurrentPrincipal currentPrincipal,
                          ChannelStore channelStore,
                          MessageStore messageStore,
                          io.casehub.qhorus.api.store.ChannelMembershipStore membershipStore,
                          ChannelBindingStore channelBindingStore,
                          ChannelGateway channelGateway,
                          ChannelCreateHelper channelCreateHelper) {
        this.currentPrincipal = currentPrincipal;
        this.channelStore = channelStore;
        this.messageStore = messageStore;
        this.membershipStore = membershipStore;
        this.channelBindingStore = channelBindingStore;
        this.channelGateway = channelGateway;
        this.channelCreateHelper = channelCreateHelper;
    }

    @Override
    @Transactional
    public Channel create(final ChannelCreateRequest req) {
        FindOrCreateResult result = channelCreateHelper.createInNewTransaction(req, false);
        if (req.hasConnectorBinding() && !result.wasCreated()) {
            throw new IllegalStateException(
                    "Connector binding already exists for connector '"
                    + req.inboundConnectorId() + "' key '" + req.externalKey() + "'");
        }
        return result.channel();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public FindOrCreateResult findOrCreate(final ChannelCreateRequest req) {
        if (req.hasConnectorBinding()) {
            return findOrCreateWithBinding(req);
        }
        return findOrCreateByName(req);
    }

    private FindOrCreateResult findOrCreateWithBinding(final ChannelCreateRequest req) {
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

        try {
            return channelCreateHelper.createInNewTransaction(req, true);
        } catch (PersistenceException | IllegalStateException ex) {
            Optional<ChannelConnectorBinding> winner = channelBindingStore
                    .findByKey(req.inboundConnectorId(), req.externalKey());
            if (winner.isPresent()) {
                Channel existing = channelStore.find(winner.get().channelId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Race recovery failed: binding exists for key '" + req.externalKey()
                                + "' but referenced channel was deleted"));
                return new FindOrCreateResult(existing, false);
            }
            throw ex instanceof PersistenceException pe ? pe
                    : new PersistenceException(ex.getMessage(), ex);
        }
    }

    private FindOrCreateResult findOrCreateByName(final ChannelCreateRequest req) {
        Optional<Channel> existing = channelStore.findByName(req.name());
        if (existing.isPresent()) {
            return new FindOrCreateResult(existing.get(), false);
        }
        try {
            return channelCreateHelper.createInNewTransaction(req, false);
        } catch (PersistenceException ex) {
            Optional<Channel> winner = channelStore.findByName(req.name());
            if (winner.isPresent()) {
                return new FindOrCreateResult(winner.get(), false);
            }
            throw ex;
        }
    }

    @Override
    @Transactional
    public Channel setRateLimits(UUID channelId, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .rateLimitPerChannel(rateLimitPerChannel)
                .rateLimitPerInstance(rateLimitPerInstance).build());
    }

    @Override
    @Transactional
    public Channel setAllowedWriters(UUID channelId, List<String> allowedWriters) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .allowedWriters(allowedWriters).build());
    }

    @Override
    @Transactional
    public Channel setAdminInstances(UUID channelId, List<String> adminInstances) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder()
                .adminInstances(adminInstances).build());
    }

    @Override
    @Transactional
    public Channel setReviewerInstances(UUID channelId, List<String> reviewerInstances) {
        Channel ch = channelStore.find(channelId).orElseThrow(() ->
                new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().reviewerInstances(reviewerInstances).build());
    }

    @Override
    @Transactional
    public Channel setProtocols(UUID channelId, List<String> protocols) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().protocols(protocols).build());
    }

    @Override
    @Transactional
    public Channel setProtocolParticipants(UUID channelId, List<String> protocolParticipants) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().protocolParticipants(protocolParticipants).build());
    }

    @Transactional
    public Channel setEnforcementMode(UUID channelId, io.casehub.qhorus.api.channel.EnforcementMode mode) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().enforcementMode(mode).build());
    }

    @Transactional
    public Channel setRoutingTrustThreshold(UUID channelId, Double threshold) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().routingTrustThreshold(threshold).build());
    }

    @Transactional
    public Channel setRedistributionCapacityThreshold(UUID channelId, Double threshold) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().redistributionCapacityThreshold(threshold).build());
    }

    @Transactional
    public Channel setRoutingCapacityThreshold(UUID channelId, Double threshold) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().routingCapacityThreshold(threshold).build());
    }

    @Transactional
    public Channel setPolicyOverrides(UUID channelId, java.util.Map<String, String> overrides) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        java.util.Map<String, String> merged;
        if (overrides == null) {
            merged = null;
        } else {
            merged = new java.util.LinkedHashMap<>();
            if (ch.policyOverrides() != null) {
                merged.putAll(ch.policyOverrides());
            }
            for (var entry : overrides.entrySet()) {
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            if (merged.isEmpty()) {
                merged = null;
            }
        }
        return channelStore.put(ch.toBuilder().policyOverrides(merged).build());
    }

    @Transactional
    public Channel setMetadata(UUID channelId, java.util.Map<String, String> metadata) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        java.util.Map<String, String> merged;
        if (metadata == null) {
            merged = null;
        } else {
            merged = new java.util.LinkedHashMap<>();
            if (ch.metadata() != null) {
                merged.putAll(ch.metadata());
            }
            for (var entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            if (merged.isEmpty()) {
                merged = null;
            }
        }
        return channelStore.put(ch.toBuilder().metadata(merged).build());
    }

    @Transactional
    public Channel setEnforcementExclusions(UUID channelId, List<String> exclusions) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().enforcementExclusions(exclusions).build());
    }

    @Override
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

    @Override
    @Transactional
    public Channel pause(UUID channelId) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().paused(true).build());
    }

    @Override
    @Transactional
    public Channel resume(UUID channelId) {
        Channel ch = channelStore.find(channelId)
                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        return channelStore.put(ch.toBuilder().paused(false).build());
    }

    public List<Channel> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    @Override
    public List<Channel> scan(ChannelQuery query) {
        return channelStore.scan(query);
    }

    @Override
    public List<Channel> findByIds(Collection<UUID> ids) {
        return channelStore.findByIds(ids);
    }

    @Override
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

    @Transactional
    public void setTrackDelivery(UUID channelId, Boolean trackDelivery) {
        Channel ch = channelStore.find(channelId).orElseThrow(
                () -> new IllegalArgumentException("Channel not found: " + channelId));
        boolean wasEnabled = isDeliveryTrackingEnabled(ch);
        channelStore.updateTrackDelivery(channelId, trackDelivery);
        Channel updated    = ch.toBuilder().trackDelivery(trackDelivery).build();
        boolean nowEnabled = isDeliveryTrackingEnabled(updated);
        if (!wasEnabled && nowEnabled) {
            Long latestId = messageStore.findLastMessage(channelId).map(io.casehub.qhorus.api.message.Message::id).orElse(null);
            if (latestId != null) {
                Set<String> memberIds = membershipStore.findByChannel(channelId).stream()
                        .map(io.casehub.qhorus.api.channel.ChannelMembership::memberId)
                        .collect(Collectors.toSet());
                if (!memberIds.isEmpty()) {
                    membershipStore.advanceDeliveredCursorForMembers(channelId, memberIds, latestId);
                }
            }
        }
    }

    public static boolean isDeliveryTrackingEnabled(Channel ch) {
        if (ch.trackDelivery() != null) { return ch.trackDelivery(); }
        return ch.semantic() == ChannelSemantic.BARRIER
               || ch.semantic() == ChannelSemantic.COLLECT;
    }
}

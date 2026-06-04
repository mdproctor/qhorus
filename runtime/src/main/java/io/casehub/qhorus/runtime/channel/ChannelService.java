package io.casehub.qhorus.runtime.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

@ApplicationScoped
public class ChannelService {

    @Inject
    ChannelStore channelStore;

    @Inject
    MessageStore messageStore;

    @Inject
    ChannelBindingStore channelBindingStore;

    @Inject
    Event<ChannelInitialisedEvent> channelInitialisedEvents;

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors) {
        return create(name, description, semantic, barrierContributors, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters) {
        return create(name, description, semantic, barrierContributors, allowedWriters, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances) {
        return create(name, description, semantic, barrierContributors, allowedWriters, adminInstances, null, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        return create(name, description, semantic, barrierContributors, allowedWriters,
                adminInstances, rateLimitPerChannel, rateLimitPerInstance, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances, Integer rateLimitPerChannel, Integer rateLimitPerInstance,
            String allowedTypes) {
        return create(name, description, semantic, barrierContributors,
                allowedWriters, adminInstances, rateLimitPerChannel, rateLimitPerInstance,
                allowedTypes, null);
    }

    @Transactional
    public Channel create(String name, String description, ChannelSemantic semantic, String barrierContributors,
            String allowedWriters, String adminInstances, Integer rateLimitPerChannel, Integer rateLimitPerInstance,
            String allowedTypes, String deniedTypes) {
        return create(new ChannelCreateRequest(
                name, description, semantic, barrierContributors,
                allowedWriters, adminInstances, rateLimitPerChannel, rateLimitPerInstance,
                allowedTypes, deniedTypes,
                null, null, null, null));
    }

    /**
     * Creates a channel from a {@link ChannelCreateRequest}, optionally persisting a connector binding.
     *
     * <p>If the request has a connector binding ({@link ChannelCreateRequest#hasConnectorBinding()}),
     * the binding is stored after the channel is created. A duplicate binding for the same
     * {@code inboundConnectorId + externalKey} pair throws {@link IllegalStateException}.
     */
    @Transactional
    public Channel create(final ChannelCreateRequest req) {
        Channel channel = populateChannel(req);
        channelStore.put(channel);

        if (req.hasConnectorBinding()) {
            channelBindingStore.findByKey(req.inboundConnectorId(), req.externalKey())
                    .ifPresent(existing -> {
                        throw new IllegalStateException(
                                "Connector binding already exists for connector '"
                                + req.inboundConnectorId() + "' key '" + req.externalKey() + "'");
                    });
            ChannelConnectorBinding binding = new ChannelConnectorBinding();
            binding.channelId = channel.id;
            binding.inboundConnectorId = req.inboundConnectorId();
            binding.externalKey = req.externalKey();
            binding.outboundConnectorId = req.outboundConnectorId();
            binding.outboundDestination = req.outboundDestination();
            channelBindingStore.put(binding);
        }

        return channel;
    }

    /**
     * Finds an existing channel by connector binding key, or creates one atomically if not found.
     *
     * <p>Runs in {@code REQUIRES_NEW} so the channel and binding commit independently of any
     * outer transaction. The commit happens at the CDI proxy boundary when this method returns
     * — not inside the method body. A unique constraint violation on {@code uq_binding_key}
     * therefore surfaces in the <em>caller</em> as {@link jakarta.persistence.PersistenceException}.
     *
     * @param req must have {@link ChannelCreateRequest#hasConnectorBinding()} == true
     * @return result indicating the channel and whether it was newly created in this call
     * @throws IllegalArgumentException if {@code req} has no connector binding
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public FindOrCreateResult findOrCreateWithBinding(final ChannelCreateRequest req) {
        if (!req.hasConnectorBinding()) {
            throw new IllegalArgumentException("findOrCreateWithBinding requires a connector binding");
        }
        // Recheck under transaction
        Optional<ChannelConnectorBinding> existingBinding = channelBindingStore
                .findByKey(req.inboundConnectorId(), req.externalKey());
        if (existingBinding.isPresent()) {
            Channel existing = channelStore.find(existingBinding.get().channelId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Stale binding: binding exists for key '" + req.externalKey()
                            + "' (connector=" + req.inboundConnectorId()
                            + ") but referenced channel was deleted"));
            return new FindOrCreateResult(existing, false);
        }

        Channel channel = populateChannel(req);
        channel.autoCreated = true;
        channelStore.put(channel);

        ChannelConnectorBinding binding = new ChannelConnectorBinding();
        binding.channelId = channel.id;
        binding.inboundConnectorId = req.inboundConnectorId();
        binding.externalKey = req.externalKey();
        binding.outboundConnectorId = req.outboundConnectorId();
        binding.outboundDestination = req.outboundDestination();
        channelBindingStore.put(binding);

        return new FindOrCreateResult(channel, true);
    }

    @Transactional
    public Channel setRateLimits(String name, Integer rateLimitPerChannel, Integer rateLimitPerInstance) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.rateLimitPerChannel = rateLimitPerChannel;
        ch.rateLimitPerInstance = rateLimitPerInstance;
        return ch;
    }

    @Transactional
    public Channel setAllowedWriters(String name, String allowedWriters) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.allowedWriters = (allowedWriters == null || allowedWriters.isBlank()) ? null : allowedWriters;
        return ch;
    }

    @Transactional
    public Channel setAdminInstances(String name, String adminInstances) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.adminInstances = (adminInstances == null || adminInstances.isBlank()) ? null : adminInstances;
        return ch;
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

    /**
     * Finds a channel by its inbound connector key.
     *
     * <p>Looks up the {@link io.casehub.qhorus.runtime.channel.ChannelConnectorBinding}
     * for the given connector and external key, then resolves the channel entity.
     *
     * @param connectorId     the inbound connector identifier (e.g. {@code "twilio-sms-inbound"})
     * @param externalKey     the connector-specific lookup key (sender ID or channel ref)
     * @return the matching channel, or empty if no binding exists for this key
     */
    public Optional<Channel> findByConnectorKey(String connectorId, String externalKey) {
        return channelBindingStore.findByKey(connectorId, externalKey)
                .flatMap(binding -> channelStore.find(binding.channelId));
    }

    @Transactional
    public Channel pause(String name) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.paused = true;
        return ch;
    }

    @Transactional
    public Channel resume(String name) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        ch.paused = false;
        return ch;
    }

    public List<Channel> listAll() {
        return channelStore.scan(ChannelQuery.all());
    }

    /**
     * Delete a channel by name. When {@code force=true}, purges all messages in the channel
     * before deletion (required — {@code fk_message_channel} has no CASCADE).
     *
     * @param name the channel name
     * @param force when false, rejects if the channel has messages
     * @return number of messages deleted
     * @throws IllegalArgumentException if the channel does not exist
     * @throws IllegalStateException if force=false and the channel has messages
     */
    @Transactional
    public long delete(final String name, final boolean force) {
        Channel ch = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
        int messageCount = messageStore.countByChannel(ch.id);
        if (messageCount > 0 && !force) {
            throw new IllegalStateException(
                    "Channel '" + name + "' has " + messageCount
                            + " messages. Pass force=true to delete anyway.");
        }
        if (messageCount > 0) {
            messageStore.deleteAll(ch.id);
        }
        channelStore.delete(ch.id);
        return messageCount;
    }

    /**
     * Updates the outbound connector fields of an existing connector binding and fires
     * {@link ChannelInitialisedEvent} so that observers (e.g. ConnectorChannelBackend)
     * refresh their in-memory cache.
     *
     * @param channelId            the channel whose binding to update
     * @param outboundConnectorId  new outbound connector identifier
     * @param outboundDestination  new outbound destination (e.g. webhook URL, phone number)
     * @throws IllegalArgumentException if no channel exists with the given id
     * @throws IllegalStateException    if the channel has no connector binding
     * Refs #215
     */
    @Transactional
    public ChannelConnectorBinding updateConnectorBinding(UUID channelId,
            String outboundConnectorId, String outboundDestination) {
        Channel channel = channelStore.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        ChannelConnectorBinding binding = channelBindingStore.findByChannelId(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "No connector binding for channel: " + channelId));
        binding.outboundConnectorId = outboundConnectorId;
        binding.outboundDestination = outboundDestination;
        channelInitialisedEvents.fire(new ChannelInitialisedEvent(channel.id, channel.name, false));
        return binding;
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
        channel.allowedTypes = blankToNull(req.allowedTypes());
        channel.deniedTypes = blankToNull(req.deniedTypes());
        return channel;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @Transactional
    public void updateLastActivity(UUID channelId) {
        Channel channel = channelStore.find(channelId).orElse(null);
        if (channel != null) {
            channel.lastActivityAt = Instant.now();
        }
    }
}

package io.casehub.qhorus.runtime.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
import io.casehub.qhorus.runtime.store.CrossTenantMessageStore;
import io.casehub.qhorus.runtime.store.DeliveryCursorStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

/**
 * Transactional helper for {@link DeliveryService}. Separated into its own CDI bean
 * so that {@code @Transactional} works via the CDI proxy — self-invocation on
 * {@code DeliveryService} would bypass the interceptor.
 *
 * <p>Package-private — only {@code DeliveryService} should call these methods.
 *
 * <p>Refs #132.
 */
@ApplicationScoped
class DeliveryBatchExecutor {

    private static final Logger LOG = Logger.getLogger(DeliveryBatchExecutor.class);

    @Inject
    @CrossTenant
    CrossTenantMessageStore messageStore;

    @Inject
    @CrossTenant
    CrossTenantChannelStore channelStore;

    @Inject
    DeliveryCursorStore cursorStore;

    @Inject
    DeliveryConfig config;

    /** For CDI-free unit tests. */
    DeliveryBatchExecutor() {
    }

    DeliveryBatchExecutor(CrossTenantMessageStore messageStore,
                          CrossTenantChannelStore channelStore,
                          DeliveryCursorStore cursorStore,
                          DeliveryConfig config) {
        this.messageStore = messageStore;
        this.channelStore = channelStore;
        this.cursorStore = cursorStore;
        this.config = config;
    }

    enum Status {
        EMPTY,
        MORE,
        FAILED
    }

    record BatchResult(Status status, int deliveredCount) {
        static final BatchResult EMPTY = new BatchResult(Status.EMPTY, 0);
        static final BatchResult FAILED = new BatchResult(Status.FAILED, 0);
    }

    /**
     * Delivers a batch of pending messages to the given backend. Each call gets its own
     * transaction (via CDI proxy), giving a fresh persistence context per iteration.
     *
     * @param channelId the channel to deliver from
     * @param backend   the backend to deliver to
     * @param healthCallback callback for recording failures / resetting health
     * @return the batch result indicating whether to continue, stop, or that there are no more messages
     */
    @Transactional
    BatchResult deliverBatch(UUID channelId, ChannelBackend backend, HealthCallback healthCallback) {
        // Resolve channel — one query per batch, needed for ChannelRef
        Optional<Channel> channelOpt = channelStore.findById(channelId);
        if (channelOpt.isEmpty()) {
            return BatchResult.FAILED; // channel deleted
        }
        Channel channel = channelOpt.get();

        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, backend.backendId())
                .orElseGet(() -> initializeCursor(channelId, backend.backendId()));
        if (cursor == null) {
            return BatchResult.FAILED; // channel deleted during init
        }

        Long startCursor = cursor.lastDeliveredId; // for failure-path conditional save

        List<Message> batch = messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(cursor.lastDeliveredId)
                        .afterVersion(cursor.lastDeliveredVersion)
                        .limit(config.batchSize())
                        .build());
        if (batch.isEmpty()) {
            return BatchResult.EMPTY;
        }

        ChannelRef ref = new ChannelRef(channelId, channel.name);
        int delivered = 0;
        for (Message m : batch) {
            try {
                backend.post(ref, toOutbound(m));
                cursor.lastDeliveredId = m.id;
                cursor.lastDeliveredVersion = m.version;
                cursor.updatedAt = Instant.now();
                delivered++;
            } catch (Exception e) {
                LOG.warnf(e, "Backend %s failed to deliver message %d on channel %s",
                        backend.backendId(), m.id, channelId);
                healthCallback.recordFailure(backend.backendId());
                // Save cursor only if we successfully delivered at least one message
                if (!cursor.lastDeliveredId.equals(startCursor)) {
                    cursorStore.save(cursor);
                }
                return new BatchResult(Status.FAILED, delivered);
            }
        }
        // All messages in batch delivered successfully — advance cursor once
        cursorStore.save(cursor);
        healthCallback.resetHealth(backend.backendId());
        return new BatchResult(Status.MORE, delivered);
    }

    /**
     * Initializes a cursor at the current message HEAD — all existing messages are skipped.
     * This is a deliberate "start from now" policy.
     *
     * <p>Called only from {@link #deliverBatch}, which is already transactional.
     * {@code @Transactional} is not needed here — self-invocation would bypass the proxy anyway.
     *
     * @return the saved cursor, or {@code null} if the channel was deleted during init
     */
    DeliveryCursor initializeCursor(UUID channelId, String backendId) {
        Long head = messageStore.findLastMessage(channelId).map(m -> m.id).orElse(0L);
        DeliveryCursor cursor = new DeliveryCursor();
        cursor.channelId = channelId;
        cursor.backendId = backendId;
        cursor.lastDeliveredId = head;
        cursor.createdAt = Instant.now();
        cursor.updatedAt = Instant.now();
        try {
            return cursorStore.save(cursor);
        } catch (PersistenceException e) {
            // Channel deleted between check and save — abort delivery for this channel
            LOG.debugf("Channel %s deleted during cursor init for backend %s — aborting",
                    channelId, backendId);
            return null;
        }
    }

    /**
     * Converts a persisted {@link Message} to an {@link OutboundMessage} for backend delivery.
     */
    static OutboundMessage toOutbound(Message m) {
        return new OutboundMessage(
                UUID.randomUUID(),
                m.sender,
                m.messageType,
                m.content,
                m.correlationId != null ? UUID.fromString(m.correlationId) : null,
                m.inReplyTo,
                ActorTypeResolver.resolve(m.sender));
    }

    /**
     * Callback interface for health tracking. {@link DeliveryService} implements this
     * to decouple health state from the transactional batch executor.
     */
    interface HealthCallback {
        void recordFailure(String backendId);
        void resetHealth(String backendId);
    }
}

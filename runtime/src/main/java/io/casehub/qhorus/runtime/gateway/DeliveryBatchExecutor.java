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

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
class DeliveryBatchExecutor {

    private static final Logger LOG = Logger.getLogger(DeliveryBatchExecutor.class);

    @Inject CrossTenantMessageStore messageStore;
    @Inject CrossTenantChannelStore channelStore;
    @Inject DeliveryCursorStore cursorStore;
    @Inject DeliveryConfig config;

    DeliveryBatchExecutor() {}

    DeliveryBatchExecutor(CrossTenantMessageStore messageStore,
                          CrossTenantChannelStore channelStore,
                          DeliveryCursorStore cursorStore,
                          DeliveryConfig config) {
        this.messageStore = messageStore;
        this.channelStore = channelStore;
        this.cursorStore = cursorStore;
        this.config = config;
    }

    enum Status { EMPTY, MORE, FAILED }

    record BatchResult(Status status, int deliveredCount) {
        static final BatchResult EMPTY = new BatchResult(Status.EMPTY, 0);
        static final BatchResult FAILED = new BatchResult(Status.FAILED, 0);
    }

    @Transactional
    BatchResult deliverBatch(UUID channelId, ChannelBackend backend, HealthCallback healthCallback) {
        Optional<Channel> channelOpt = channelStore.findById(channelId);
        if (channelOpt.isEmpty()) return BatchResult.FAILED;
        Channel channel = channelOpt.get();

        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, backend.backendId())
                .orElseGet(() -> initializeCursor(channelId, backend.backendId()));
        if (cursor == null) return BatchResult.FAILED;

        Long startCursor = cursor.lastDeliveredId();

        List<Message> batch = messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(cursor.lastDeliveredId())
                        .afterVersion(cursor.lastDeliveredVersion())
                        .limit(config.batchSize())
                        .build());
        if (batch.isEmpty()) return BatchResult.EMPTY;

        ChannelRef ref = new ChannelRef(channelId, channel.name());
        int delivered = 0;
        for (Message m : batch) {
            try {
                backend.post(ref, toOutbound(m));
                cursor = cursor.toBuilder()
                        .lastDeliveredId(m.id())
                        .lastDeliveredVersion(m.version())
                        .updatedAt(Instant.now())
                        .build();
                delivered++;
            } catch (Exception e) {
                LOG.warnf(e, "Backend %s failed to deliver message %d on channel %s",
                        backend.backendId(), m.id(), channelId);
                healthCallback.recordFailure(backend.backendId());
                if (!cursor.lastDeliveredId().equals(startCursor)) {
                    cursorStore.save(cursor);
                }
                return new BatchResult(Status.FAILED, delivered);
            }
        }
        cursorStore.save(cursor);
        healthCallback.resetHealth(backend.backendId());
        return new BatchResult(Status.MORE, delivered);
    }

    DeliveryCursor initializeCursor(UUID channelId, String backendId) {
        Long head = messageStore.findLastMessage(channelId).map(Message::id).orElse(0L);
        DeliveryCursor cursor = DeliveryCursor.builder()
                .channelId(channelId)
                .backendId(backendId)
                .lastDeliveredId(head)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        try {
            return cursorStore.save(cursor);
        } catch (PersistenceException e) {
            LOG.debugf("Channel %s deleted during cursor init for backend %s — aborting",
                    channelId, backendId);
            return null;
        }
    }

    static OutboundMessage toOutbound(Message m) {
        return new OutboundMessage(
                UUID.randomUUID(),
                m.sender(),
                m.messageType(),
                m.content(),
                m.correlationId() != null ? UUID.fromString(m.correlationId()) : null,
                m.inReplyTo(),
                ActorTypeResolver.resolve(m.sender()));
    }

    interface HealthCallback {
        void recordFailure(String backendId);
        void resetHealth(String backendId);
    }
}

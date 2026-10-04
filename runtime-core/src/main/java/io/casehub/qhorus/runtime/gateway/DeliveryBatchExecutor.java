package io.casehub.qhorus.runtime.gateway;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.gateway.PostResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DeliveryBatchExecutor {

    private static final Logger LOG = Logger.getLogger(DeliveryBatchExecutor.class);

    CrossTenantMessageStore messageStore;
    CrossTenantChannelStore channelStore;
    DeliveryCursorStore cursorStore;
    DeliveryConfig config;
    io.casehub.qhorus.api.store.ChannelMembershipStore channelMembershipStore;
    Supplier<Tracer> tracerSupplier;
    QhorusTracingConfig tracingConfig;


    protected DeliveryBatchExecutor() {}

    public DeliveryBatchExecutor(CrossTenantMessageStore messageStore,
                                 CrossTenantChannelStore channelStore,
                                 DeliveryCursorStore cursorStore,
                                 DeliveryConfig config,
                                 io.casehub.qhorus.api.store.ChannelMembershipStore channelMembershipStore,
                                 Supplier<Tracer> tracerSupplier,
                                 QhorusTracingConfig tracingConfig) {
        this.messageStore = messageStore;
        this.channelStore = channelStore;
        this.cursorStore = cursorStore;
        this.config = config;
        this.channelMembershipStore = channelMembershipStore;
        this.tracerSupplier = tracerSupplier;
        this.tracingConfig = tracingConfig;
    }

    public enum Status { EMPTY, MORE, FAILED }

    public record BatchResult(Status status, int deliveredCount) {
        public static final BatchResult EMPTY = new BatchResult(Status.EMPTY, 0);
        public static final BatchResult FAILED = new BatchResult(Status.FAILED, 0);
    }

    @Transactional
    public BatchResult deliverBatch(UUID channelId, ChannelBackend backend, HealthCallback healthCallback) {
        Span span = null;
        if (tracingConfig != null && tracingConfig.enabled() && tracingConfig.delivery() && tracerSupplier != null) {
            Tracer tracer = tracerSupplier.get();
            span = tracer.spanBuilder("qhorus.delivery.pump")
                         .setNoParent()
                         .setSpanKind(SpanKind.INTERNAL)
                         .startSpan();
            span.setAttribute("qhorus.channel.id", channelId.toString());
            span.setAttribute("qhorus.delivery.backend_id", backend.backendId());
        }
        try {
            Optional<Channel> channelOpt = channelStore.findById(channelId);
            if (channelOpt.isEmpty()) { return BatchResult.FAILED; }
            Channel channel = channelOpt.get();

            DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, backend.backendId())
                                               .orElseGet(() -> initializeCursor(channelId, backend.backendId()));
            if (cursor == null) { return BatchResult.FAILED; }

            if (span != null) {
                span.setAttribute("qhorus.delivery.cursor_position", cursor.lastDeliveredId());
            }

            Long startCursor = cursor.lastDeliveredId();

            List<Message> batch = messageStore.scan(
                    MessageQuery.builder()
                                .channelId(channelId)
                                .afterId(cursor.lastDeliveredId())
                                .afterVersion(cursor.lastDeliveredVersion())
                                .limit(config.batchSize())
                                .build());
            if (batch.isEmpty()) { return BatchResult.EMPTY; }

            if (span != null) {
                span.setAttribute("qhorus.delivery.batch_size", batch.size());
            }

            boolean trackDelivery = io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(channel);
            Set<String> deliveryMemberIds = Set.of();
            if (trackDelivery && channelMembershipStore != null) {
                deliveryMemberIds = channelMembershipStore.findByChannel(channelId).stream()
                        .filter(mem -> ActorTypeResolver.resolve(mem.memberId()) == backend.actorType())
                        .map(io.casehub.qhorus.api.channel.ChannelMembership::memberId)
                        .collect(Collectors.toSet());
            }

            ChannelRef ref       = new ChannelRef(channelId, channel.name());
            int        delivered = 0;
            for (Message m : batch) {
                try {
                    PostResult result = backend.postTracked(ref, toOutbound(m));
                    cursor = cursor.toBuilder()
                                   .lastDeliveredId(m.id())
                                   .lastDeliveredVersion(m.version())
                                   .updatedAt(Instant.now())
                                   .build();
                    delivered++;
                    if (trackDelivery && !deliveryMemberIds.isEmpty()) {
                        Set<String> successfulMembers = new HashSet<>(deliveryMemberIds);
                        successfulMembers.removeAll(result.failedParticipantIds());
                        if (!successfulMembers.isEmpty()) {
                            channelMembershipStore.advanceDeliveredCursorForMembers(channelId, successfulMembers, m.id());
                        }
                    }
                } catch (Exception e) {
                    if (span != null) {
                        span.setStatus(StatusCode.ERROR);
                        span.recordException(e);
                    }
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
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) { span.end(); }
        }
    }

    public DeliveryCursor initializeCursor(UUID channelId, String backendId) {
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

    public static OutboundMessage toOutbound(Message m) {
        return new OutboundMessage(
                UUID.randomUUID(),
                m.id(),
                m.sender(),
                m.messageType(),
                m.content(),
                m.payload(),
                m.correlationId(),
                m.inReplyTo(),
                m.actorType(),
                m.artefactRefs(),
                m.target(),
                m.topic(),
                m.invocationContext());
    }

    public interface HealthCallback {
        void recordFailure(String backendId);
        void resetHealth(String backendId);
    }
}

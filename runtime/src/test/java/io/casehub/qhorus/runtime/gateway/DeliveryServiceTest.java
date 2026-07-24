package io.casehub.qhorus.runtime.gateway;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CDI-free unit tests for {@link DeliveryService} and {@link DeliveryBatchExecutor}.
 * Uses local in-test stubs (no dependency on casehub-qhorus-testing to avoid build cycle).
 *
 * <p>The pump thread is NOT started — tests call {@code processChannel()} and
 * {@code deliverBatch()} directly.
 *
 * <p>Refs #132.
 */
class DeliveryServiceTest {

    // ── Test stubs ───────────────────────────────────────────────────────────────

    /** Minimal in-test recording backend with configurable delivery guarantee. */
    static class TestBackend implements ChannelBackend {
        private final String id;
        private final DeliveryGuarantee guarantee;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private volatile RuntimeException throwOnPost;
        private volatile boolean alwaysThrow;

        TestBackend(String id, DeliveryGuarantee guarantee) {
            this.id = id;
            this.guarantee = guarantee;
        }

        void throwOnNextPost(RuntimeException ex) {
            this.throwOnPost = ex;
        }

        void alwaysThrowOnPost(RuntimeException ex) {
            this.throwOnPost = ex;
            this.alwaysThrow = true;
        }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return ActorType.SYSTEM; }
        @Override public DeliveryGuarantee deliveryGuarantee() { return guarantee; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            RuntimeException ex = throwOnPost;
            if (ex != null) {
                if (!alwaysThrow) throwOnPost = null;
                throw ex;
            }
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
        void clear() { posts.clear(); throwOnPost = null; alwaysThrow = false; }
    }

    static class StubDeliveryCursorStore implements DeliveryCursorStore {
        private final Map<UUID, DeliveryCursor> byId = new LinkedHashMap<>();

        @Override
        public DeliveryCursor save(DeliveryCursor c) {
            if (c.id() == null) c = c.toBuilder().id(UUID.randomUUID()).build();
            if (c.createdAt() == null) c = c.toBuilder().createdAt(Instant.now()).build();
            byId.put(c.id(), c);
            return c;
        }

        @Override
        public Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId) {
            return byId.values().stream()
                    .filter(c -> channelId.equals(c.channelId()) && backendId.equals(c.backendId()))
                    .findFirst();
        }

        @Override
        public List<DeliveryCursor> findByChannel(UUID channelId) {
            return byId.values().stream().filter(c -> channelId.equals(c.channelId())).toList();
        }

        @Override
        public List<DeliveryCursor> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void deleteByChannel(UUID channelId) {
            byId.values().removeIf(c -> channelId.equals(c.channelId()));
        }

        void clear() { byId.clear(); }
    }

    static class StubCrossTenantMessageStore implements CrossTenantMessageStore {
        private final Map<Long, Message> byId      = new LinkedHashMap<>();
        private final AtomicLong         idCounter = new AtomicLong(1);

        Message put(Message m) {
            if (m.id() == null) m = m.toBuilder().id(idCounter.getAndIncrement()).build();
            if (m.createdAt() == null) m = m.toBuilder().createdAt(Instant.now()).build();
            byId.put(m.id(), m);
            return m;
        }

        @Override
        public List<Message> scan(MessageQuery query) {
            return byId.values().stream()
                    .filter(query::matches)
                    .sorted((a, b) -> Long.compare(a.id(), b.id()))
                    .limit(query.limit() != null ? query.limit() : Integer.MAX_VALUE)
                    .toList();
        }

        @Override
        public long count(MessageQuery query) {
            return byId.values().stream().filter(query::matches).count();
        }

        @Override
        public int countByChannel(UUID channelId) {
            return (int) byId.values().stream().filter(m -> channelId.equals(m.channelId())).count();
        }

        @Override
        public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
            return byId.values().stream()
                    .filter(m -> channelId.equals(m.channelId()) && m.messageType() != excludedType)
                    .map(m -> m.sender())
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Override
        public Optional<Message> findLastMessage(UUID channelId) {
            return byId.values().stream()
                    .filter(m -> channelId.equals(m.channelId()))
                    .reduce((a, b) -> b); // last by insertion order = highest ID
        }

        @Override
        public Optional<Message> find(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        void clear() { byId.clear(); idCounter.set(1); }
    }

    /** Minimal in-test CrossTenantChannelStore. */
    static class StubCrossTenantChannelStore implements CrossTenantChannelStore {
        private final Map<UUID, Channel> byId = new LinkedHashMap<>();

        Channel put(Channel ch) {
            if (ch.id() == null) ch = ch.toBuilder().id(UUID.randomUUID()).build();
            byId.put(ch.id(), ch);
            return ch;
        }

        @Override
        public List<Channel> listAll() { return List.copyOf(byId.values()); }

        @Override
        public Optional<Channel> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
            return byId.values().stream()
                    .filter(c -> name.equals(c.name()))
                    .findFirst();
        }

        void remove(UUID id) { byId.remove(id); }
        void clear() { byId.clear(); }
    }

    /** Stub DeliveryConfig with configurable values. */
    static class StubDeliveryConfig implements DeliveryConfig {
        private final boolean enabled;
        private final int batchSize;
        private final int maxConsecutiveFailures;

        StubDeliveryConfig(boolean enabled, int batchSize, int maxConsecutiveFailures) {
            this.enabled = enabled;
            this.batchSize = batchSize;
            this.maxConsecutiveFailures = maxConsecutiveFailures;
        }

        @Override public boolean enabled() { return enabled; }
        @Override public int batchSize() { return batchSize; }
        @Override public int maxConsecutiveFailures() { return maxConsecutiveFailures; }
        @Override public String reconciliationInterval() { return "30s"; }
    }

    // ── Test fixtures ────────────────────────────────────────────────────────────

    StubDeliveryCursorStore cursorStore;
    StubCrossTenantMessageStore messageStore;
    StubCrossTenantChannelStore channelStore;
    StubDeliveryConfig deliveryConfig;
    DeliveryBatchExecutor batchExecutor;
    DeliveryService service;
    SimpleMeterRegistry meterRegistry;

    UUID          channelId;
    Channel channel;
    TestBackend   trackedBackend;

    /** Stub ChannelGateway — overrides trackedEntries() to return test-controlled data. */
    ChannelGateway stubGateway;

    @BeforeEach
    void setUp() {
        cursorStore = new StubDeliveryCursorStore();
        messageStore = new StubCrossTenantMessageStore();
        channelStore = new StubCrossTenantChannelStore();
        deliveryConfig = new StubDeliveryConfig(true, 100, 3);

        batchExecutor = new DeliveryBatchExecutor(messageStore, channelStore, cursorStore, deliveryConfig);
        meterRegistry = new SimpleMeterRegistry();

        channelId = UUID.randomUUID();
        channel = channelStore.put(Channel.builder("test-channel").id(channelId).build());

        trackedBackend = new TestBackend("tracked-1", DeliveryGuarantee.AT_LEAST_ONCE);

        // Create a stub gateway that returns our test backend
        stubGateway = createStubGateway(List.of(
                new ChannelGateway.BackendEntry(trackedBackend, "human_participating", null)));

        service = new DeliveryService();
        service.signalQueue = new DeliverySignalQueue();
        service.config = deliveryConfig;
        service.gateway = stubGateway;
        service.batchExecutor = batchExecutor;
        service.cursorStore = cursorStore;
        service.messageStore = messageStore;
        service.meterRegistry = meterRegistry;
        service.running = true;
        // Register gauges (normally done by @PostConstruct start())
        Gauge.builder("qhorus.delivery.backends.unhealthy", service.unhealthySet(), Set::size)
                .register(meterRegistry);
        // managedExecutor not needed — tests call processChannel()/deliverBatch() directly
    }

    // ── deliverBatch tests ───────────────────────────────────────────────────────

    @Test
    void deliverBatch_pendingMessages_deliversInOrderAndAdvancesCursor() {
        // Seed cursor at 0 — all messages pending
        createCursor(channelId, trackedBackend.backendId(), 0L);

        Message m1 = addMessage(channelId, "alice", MessageType.COMMAND, "do task 1");
        Message m2 = addMessage(channelId, "alice", MessageType.COMMAND, "do task 2");
        Message m3 = addMessage(channelId, "bob", MessageType.STATUS, "working on it");

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.MORE);
        assertThat(trackedBackend.posts()).hasSize(3);
        assertThat(trackedBackend.posts().get(0).content()).isEqualTo("do task 1");
        assertThat(trackedBackend.posts().get(1).content()).isEqualTo("do task 2");
        assertThat(trackedBackend.posts().get(2).content()).isEqualTo("working on it");

        // Cursor advanced to last message
        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, trackedBackend.backendId()).orElseThrow();
        assertThat(cursor.lastDeliveredId()).isEqualTo(m3.id());
    }

    @Test
    void deliverBatch_noMessages_returnsEmpty() {
        // Seed cursor at head (no pending messages)
        Message m1 = addMessage(channelId, "alice", MessageType.COMMAND, "done");
        createCursor(channelId, trackedBackend.backendId(), m1.id());

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.EMPTY);
        assertThat(trackedBackend.posts()).isEmpty();
    }

    @Test
    void deliverBatch_postFailure_stopsAndPreservesOrder() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        Message m1 = addMessage(channelId, "alice", MessageType.COMMAND, "task 1");
        Message m2 = addMessage(channelId, "alice", MessageType.COMMAND, "task 2");
        Message m3 = addMessage(channelId, "alice", MessageType.COMMAND, "task 3");

        // Fail on second message
        TestBackend failingBackend = new TestBackend("failing-1", DeliveryGuarantee.AT_LEAST_ONCE) {
            int callCount = 0;
            @Override
            public void post(ChannelRef channel, OutboundMessage message) {
                callCount++;
                if (callCount == 2) throw new RuntimeException("network error");
                super.post(channel, message);
            }
        };

        createCursor(channelId, failingBackend.backendId(), 0L);

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, failingBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.FAILED);
        // Only first message delivered
        assertThat(failingBackend.posts()).hasSize(1);
        assertThat(failingBackend.posts().get(0).content()).isEqualTo("task 1");

        // Cursor should be at m1 — the last successfully delivered message
        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, failingBackend.backendId()).orElseThrow();
        assertThat(cursor.lastDeliveredId()).isEqualTo(m1.id());
    }

    @Test
    void deliverBatch_consecutiveFailures_marksUnhealthy() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg");

        trackedBackend.alwaysThrowOnPost(new RuntimeException("always fail"));

        // Fail repeatedly up to threshold (maxConsecutiveFailures = 3)
        for (int i = 0; i < 3; i++) {
            batchExecutor.deliverBatch(channelId, trackedBackend, service);
        }

        assertThat(service.isUnhealthy(trackedBackend.backendId())).isTrue();
    }

    @Test
    void deliverBatch_successAfterFailures_resetsHealth() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg");

        // Accumulate failures (but less than threshold)
        trackedBackend.alwaysThrowOnPost(new RuntimeException("transient"));
        batchExecutor.deliverBatch(channelId, trackedBackend, service);
        batchExecutor.deliverBatch(channelId, trackedBackend, service);
        assertThat(service.isUnhealthy(trackedBackend.backendId())).isFalse();

        // Now succeed
        trackedBackend.clear();
        batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(service.isUnhealthy(trackedBackend.backendId())).isFalse();
    }

    @Test
    void deliverBatch_noCursor_initializesAtHead() {
        // Add 3 existing messages (simulating history)
        Message m1 = addMessage(channelId, "alice", MessageType.COMMAND, "old 1");
        Message m2 = addMessage(channelId, "alice", MessageType.COMMAND, "old 2");
        Message m3 = addMessage(channelId, "alice", MessageType.COMMAND, "old 3");
        // No cursor exists

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        // Should initialize cursor at HEAD (m3) and return EMPTY (no messages after head)
        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.EMPTY);
        assertThat(trackedBackend.posts()).isEmpty();

        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, trackedBackend.backendId()).orElseThrow();
        assertThat(cursor.lastDeliveredId()).isEqualTo(m3.id());
    }

    @Test
    void deliverBatch_channelDeleted_returnsFailed() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg");

        // Delete the channel from the store
        channelStore.remove(channelId);

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.FAILED);
        assertThat(trackedBackend.posts()).isEmpty();
    }

    @Test
    void deliverBatch_preservesMessageFieldsInOutbound() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        Message m = messageStore.put(Message.builder()
                .channelId(channelId).sender("agent-1").messageType(MessageType.RESPONSE)
                .actorType(ActorType.AGENT).content("here is the result")
                .correlationId(UUID.randomUUID().toString()).inReplyTo(42L).build());

        batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(trackedBackend.posts()).hasSize(1);
        OutboundMessage out = trackedBackend.posts().get(0);
        assertThat(out.sender()).isEqualTo("agent-1");
        assertThat(out.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(out.content()).isEqualTo("here is the result");
        assertThat(out.correlationId()).isEqualTo(m.correlationId());
        assertThat(out.inReplyTo()).isEqualTo(42L);
        assertThat(out.messageId()).isNotNull(); // random UUID assigned
    }

    @Test
    void deliverBatch_nullCorrelationId_handledGracefully() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        messageStore.put(Message.builder()
                .channelId(channelId).sender("alice").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).content("status update").build());

        batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(trackedBackend.posts()).hasSize(1);
        assertThat(trackedBackend.posts().get(0).correlationId()).isNull();
    }

    // ── processChannel tests ─────────────────────────────────────────────────────

    @Test
    void processChannel_unhealthyBackend_skipped() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg");

        // Force unhealthy
        for (int i = 0; i < deliveryConfig.maxConsecutiveFailures(); i++) {
            service.recordFailure(trackedBackend.backendId());
        }
        assertThat(service.isUnhealthy(trackedBackend.backendId())).isTrue();

        // Use a synchronous executor so processChannel() runs inline
        service.executor = Runnable::run;
        service.processChannel(channelId);

        // Backend should be skipped — no posts
        assertThat(trackedBackend.posts()).isEmpty();
    }

    @Test
    void processChannel_activeDeliveryGuard_preventsDoubleProcessing() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg");

        // Simulate an active delivery by pre-populating the guard
        String key = channelId + ":" + trackedBackend.backendId();
        service.activeDeliveries().add(key);

        service.executor = Runnable::run;
        service.processChannel(channelId);

        // Guard should prevent delivery
        assertThat(trackedBackend.posts()).isEmpty();

        // Clean up guard and retry — now should deliver
        service.activeDeliveries().remove(key);
        service.processChannel(channelId);

        assertThat(trackedBackend.posts()).hasSize(1);
    }

    @Test
    void processChannel_multipleBackends_processIndependently() {
        TestBackend backend2 = new TestBackend("tracked-2", DeliveryGuarantee.AT_LEAST_ONCE);

        // Re-create gateway with two backends
        stubGateway = createStubGateway(List.of(
                new ChannelGateway.BackendEntry(trackedBackend, "human_participating", null),
                new ChannelGateway.BackendEntry(backend2, "agent", null)));
        service.gateway = stubGateway;

        createCursor(channelId, trackedBackend.backendId(), 0L);
        createCursor(channelId, backend2.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "shared message");

        service.executor = Runnable::run;
        service.processChannel(channelId);

        // Both backends should receive the message independently
        assertThat(trackedBackend.posts()).hasSize(1);
        assertThat(backend2.posts()).hasSize(1);
    }

    @Test
    void deliverPending_selfDrivesUntilCaughtUp() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        // Add more messages than one batch (batchSize=100, add 150)
        // But since we're using batchSize=100 from config, let's use a smaller batch for test
        StubDeliveryConfig smallBatchConfig = new StubDeliveryConfig(true, 2, 3);
        DeliveryBatchExecutor smallBatchExecutor =
                new DeliveryBatchExecutor(messageStore, channelStore, cursorStore, smallBatchConfig);

        service.batchExecutor = smallBatchExecutor;

        addMessage(channelId, "alice", MessageType.COMMAND, "msg-1");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-2");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-3");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-4");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-5");

        service.deliverPending(channelId, trackedBackend);

        // All 5 messages should be delivered across multiple batches (batch size = 2)
        assertThat(trackedBackend.posts()).hasSize(5);
        assertThat(trackedBackend.posts().get(0).content()).isEqualTo("msg-1");
        assertThat(trackedBackend.posts().get(4).content()).isEqualTo("msg-5");
    }

    @Test
    void deliverPending_stopsOnFailure() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        addMessage(channelId, "alice", MessageType.COMMAND, "msg-1");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-2");

        // Fail on second message
        TestBackend failOnSecond = new TestBackend("fail-second", DeliveryGuarantee.AT_LEAST_ONCE) {
            int callCount = 0;
            @Override
            public void post(ChannelRef channel, OutboundMessage message) {
                callCount++;
                if (callCount == 2) throw new RuntimeException("fail");
                super.post(channel, message);
            }
        };
        createCursor(channelId, failOnSecond.backendId(), 0L);

        service.deliverPending(channelId, failOnSecond);

        // Only first message delivered, then loop exits on FAILED
        assertThat(failOnSecond.posts()).hasSize(1);
    }

    // ── Health tracking tests ────────────────────────────────────────────────────

    @Test
    void healthTracking_belowThreshold_notUnhealthy() {
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");
        // threshold is 3, so 2 failures = still healthy
        assertThat(service.isUnhealthy("backend-x")).isFalse();
    }

    @Test
    void healthTracking_atThreshold_unhealthy() {
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");
        assertThat(service.isUnhealthy("backend-x")).isTrue();
    }

    @Test
    void healthTracking_resetClearsFailures() {
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");
        service.resetHealth("backend-x");
        // After reset, 3 more failures needed to become unhealthy
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");
        assertThat(service.isUnhealthy("backend-x")).isFalse();
    }

    @Test
    void healthTracking_resetClearsUnhealthyFlag() {
        for (int i = 0; i < 3; i++) service.recordFailure("backend-x");
        assertThat(service.isUnhealthy("backend-x")).isTrue();

        service.resetHealth("backend-x");
        assertThat(service.isUnhealthy("backend-x")).isFalse();
    }

    // ── Cursor initialization ────────────────────────────────────────────────────

    @Test
    void initializeCursor_emptyChannel_setsHeadToZero() {
        // Channel exists but has no messages
        DeliveryCursor cursor = batchExecutor.initializeCursor(channelId, "backend-1");

        assertThat(cursor).isNotNull();
        assertThat(cursor.lastDeliveredId()).isEqualTo(0L);
        assertThat(cursor.channelId()).isEqualTo(channelId);
        assertThat(cursor.backendId()).isEqualTo("backend-1");
    }

    @Test
    void initializeCursor_channelWithMessages_setsHeadToLastId() {
        addMessage(channelId, "alice", MessageType.COMMAND, "first");
        Message last = addMessage(channelId, "alice", MessageType.COMMAND, "second");

        DeliveryCursor cursor = batchExecutor.initializeCursor(channelId, "backend-1");

        assertThat(cursor).isNotNull();
        assertThat(cursor.lastDeliveredId()).isEqualTo(last.id());
    }

    // ── toOutbound ───────────────────────────────────────────────────────────────

    @Test
    void toOutbound_convertsAllFields() {
        String corrId = UUID.randomUUID().toString();
        Message m = Message.builder().id(42L).sender("agent-1").messageType(MessageType.DONE)
                .content("completed").correlationId(corrId).inReplyTo(10L).build();

        OutboundMessage out = DeliveryBatchExecutor.toOutbound(m);

        assertThat(out.messageId()).isNotNull();
        assertThat(out.sender()).isEqualTo("agent-1");
        assertThat(out.type()).isEqualTo(MessageType.DONE);
        assertThat(out.content()).isEqualTo("completed");
        assertThat(out.correlationId()).isEqualTo(corrId);
        assertThat(out.inReplyTo()).isEqualTo(10L);
    }

    @Test
    void toOutbound_nullCorrelationId_mapsToNull() {
        Message m = Message.builder().sender("alice").messageType(MessageType.STATUS)
                .content("update").build();

        OutboundMessage out = DeliveryBatchExecutor.toOutbound(m);
        assertThat(out.correlationId()).isNull();
    }

    @Test
    void toOutbound_carriesTarget() {
        Message m = Message.builder().id(1L).channelId(UUID.randomUUID()).sender("agent-a")
                           .messageType(MessageType.COMMAND).actorType(ActorType.AGENT)
                           .content("do it").target("researcher").build();
        OutboundMessage out = DeliveryBatchExecutor.toOutbound(m);
        assertThat(out.target()).isEqualTo("researcher");
    }


    // ── LAST_WRITE version-aware delivery tests ────────────────────────────────

    @Test
    void deliverBatch_lastWriteOverwrite_redeliversUpdatedMessage() {
        Message m = addMessage(channelId, "alice", MessageType.STATUS, "v0 content");
        createCursor(channelId, trackedBackend.backendId(), m.id());

        messageStore.put(m.toBuilder().content("v1 content").version(1).build());

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.MORE);
        assertThat(trackedBackend.posts()).hasSize(1);
        assertThat(trackedBackend.posts().get(0).content()).isEqualTo("v1 content");

        DeliveryCursor cursor = cursorStore.findByChannelAndBackend(channelId, trackedBackend.backendId()).orElseThrow();
        assertThat(cursor.lastDeliveredVersion()).isEqualTo(1);
    }

    @Test
    void deliverBatch_lastWriteSameVersion_returnsEmpty() {
        Message  m      = addMessage(channelId, "alice", MessageType.STATUS, "content");
        DeliveryCursor cursor = createCursor(channelId, trackedBackend.backendId(), m.id());
        cursorStore.save(cursor.toBuilder().lastDeliveredVersion(0).build());

        DeliveryBatchExecutor.BatchResult result =
                batchExecutor.deliverBatch(channelId, trackedBackend, service);

        assertThat(result.status()).isEqualTo(DeliveryBatchExecutor.Status.EMPTY);
        assertThat(trackedBackend.posts()).isEmpty();
    }

    // ── Metrics tests ─────────────────────────────────────────────────────────

    @Test
    void metrics_deliverPending_incrementsDeliveredCounter() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-1");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-2");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-3");

        service.executor = Runnable::run;
        service.processChannel(channelId);

        Counter counter = meterRegistry.find("qhorus.delivery.messages.delivered")
                .tag("backendId", trackedBackend.backendId())
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void metrics_failure_incrementsFailureCounter() {
        service.recordFailure("backend-x");
        service.recordFailure("backend-x");

        Counter counter = meterRegistry.find("qhorus.delivery.failures")
                .tag("backendId", "backend-x")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void metrics_unhealthyGauge_reflectsSetSize() {
        Gauge gauge = meterRegistry.find("qhorus.delivery.backends.unhealthy").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);

        for (int i = 0; i < deliveryConfig.maxConsecutiveFailures(); i++) {
            service.recordFailure("backend-x");
        }
        assertThat(gauge.value()).isEqualTo(1.0);

        service.resetHealth("backend-x");
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void metrics_cursorLag_computedDuringReconcile() {
        createCursor(channelId, trackedBackend.backendId(), 0L);
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-1");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-2");
        addMessage(channelId, "alice", MessageType.COMMAND, "msg-3");

        service.executor = Runnable::run;
        service.reconcileAll();

        Gauge lag = meterRegistry.find("qhorus.delivery.cursor.lag")
                .tag("backendId", trackedBackend.backendId())
                .gauge();
        assertThat(lag).isNotNull();
        assertThat(lag.value()).isEqualTo(3.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Message addMessage(UUID channelId, String sender, MessageType type, String content) {
        return messageStore.put(Message.builder()
                .channelId(channelId).sender(sender).messageType(type)
                .actorType(ActorType.AGENT).content(content).build());
    }

    private DeliveryCursor createCursor(UUID channelId, String backendId, Long lastDeliveredId) {
        return cursorStore.save(DeliveryCursor.builder()
                .channelId(channelId).backendId(backendId).lastDeliveredId(lastDeliveredId)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }


// ── Delivery tracking tests ─────────────────────────────────────────────────

    static class StubChannelMembershipStore implements io.casehub.qhorus.api.store.ChannelMembershipStore {
        private final Map<String, io.casehub.qhorus.api.channel.ChannelMembership> data = new LinkedHashMap<>();

        @Override
        public io.casehub.qhorus.api.channel.ChannelMembership put(io.casehub.qhorus.api.channel.ChannelMembership m) {
            var saved = new io.casehub.qhorus.api.channel.ChannelMembership(1L, m.channelId(), m.memberId(), m.role(), m.tenancyId(), m.joinedAt(), m.lastReadMessageId(), m.lastDeliveredMessageId());
            data.put(m.channelId() + ":" + m.memberId(), saved);
            return saved;
        }

        @Override
        public Optional<io.casehub.qhorus.api.channel.ChannelMembership> find(UUID channelId, String memberId)       {return Optional.ofNullable(data.get(channelId + ":" + memberId));}

        @Override
        public List<io.casehub.qhorus.api.channel.ChannelMembership> findByChannel(UUID channelId)                   {return data.values().stream().filter(m -> m.channelId().equals(channelId)).toList();}

        @Override
        public List<io.casehub.qhorus.api.channel.ChannelMembership> findByMember(String memberId, String tenancyId) {return List.of();}

        @Override
        public void updateRole(UUID channelId, String memberId, io.casehub.qhorus.api.channel.MemberRole role)       {}

        @Override
        public void updateLastReadMessageId(UUID channelId, String memberId, Long messageId)                         {}

        @Override
        public void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId) {
            find(channelId, memberId).ifPresent(existing -> {
                if (existing.lastDeliveredMessageId() == null || messageId > existing.lastDeliveredMessageId()) {
                    data.put(channelId + ":" + memberId, new io.casehub.qhorus.api.channel.ChannelMembership(existing.id(), existing.channelId(), existing.memberId(), existing.role(), existing.tenancyId(), existing.joinedAt(), existing.lastReadMessageId(), messageId));
                }
            });
        }

        @Override
        public void advanceDeliveredCursorForMembers(UUID channelId, Set<String> memberIds, Long messageId) {
            for (String mid : memberIds) {
                updateLastDeliveredMessageId(channelId, mid, messageId);
            }
        }

        @Override
        public boolean delete(UUID channelId, String memberId)                                              {return false;}

        @Override
        public void deleteAll(UUID channelId)                                                               {}
    }

    @Test
    void deliverBatch_trackedChannel_advancesCursorForMatchingMembers() {
        UUID barrierChannelId = UUID.randomUUID();
        channelStore.put(Channel.builder("barrier-delivery")
                                .id(barrierChannelId).semantic(io.casehub.qhorus.api.channel.ChannelSemantic.BARRIER)
                                .barrierContributors(List.of("agent:worker-1")).build());

        TestBackend humanBackend = new TestBackend("human-backend", DeliveryGuarantee.AT_LEAST_ONCE) {
            @Override
            public ActorType actorType() {return ActorType.HUMAN;}
        };
        createCursor(barrierChannelId, humanBackend.backendId(), 0L);

        StubChannelMembershipStore memStore = new StubChannelMembershipStore();
        memStore.put(new io.casehub.qhorus.api.channel.ChannelMembership(null, barrierChannelId, "human:alice",
                                                                         io.casehub.qhorus.api.channel.MemberRole.PARTICIPANT, "default", Instant.now(), null));
        memStore.put(new io.casehub.qhorus.api.channel.ChannelMembership(null, barrierChannelId, "agent:worker-1",
                                                                         io.casehub.qhorus.api.channel.MemberRole.PARTICIPANT, "default", Instant.now(), null));
        batchExecutor.channelMembershipStore = memStore;

        addMessage(barrierChannelId, "agent:worker-1", MessageType.STATUS, "data");

        var result = batchExecutor.deliverBatch(barrierChannelId, humanBackend, service);
        assertThat(result.deliveredCount()).isEqualTo(1);
        assertThat(memStore.find(barrierChannelId, "human:alice").orElseThrow().lastDeliveredMessageId()).isNotNull();
        assertThat(memStore.find(barrierChannelId, "agent:worker-1").orElseThrow().lastDeliveredMessageId()).isNull();
    }

    @Test
    void deliverBatch_untrackedChannel_doesNotAdvanceCursor() {
        createCursor(channelId, trackedBackend.backendId(), 0L);

        StubChannelMembershipStore memStore = new StubChannelMembershipStore();
        memStore.put(new io.casehub.qhorus.api.channel.ChannelMembership(null, channelId, "human:alice",
                                                                         io.casehub.qhorus.api.channel.MemberRole.PARTICIPANT, "default", Instant.now(), null));
        batchExecutor.channelMembershipStore = memStore;

        addMessage(channelId, "agent-a", MessageType.STATUS, "data");

        batchExecutor.deliverBatch(channelId, trackedBackend, service);
        assertThat(memStore.find(channelId, "human:alice").orElseThrow().lastDeliveredMessageId()).isNull();
    }

    /**
     * Creates a stub ChannelGateway that returns the given entries from trackedEntries().
     * Uses a real QhorusChannelBackend and no-ops for all other gateway dependencies.
     */
    @SuppressWarnings("unchecked")
    private ChannelGateway createStubGateway(List<ChannelGateway.BackendEntry> entries) {
        jakarta.enterprise.inject.Instance<io.opentelemetry.api.trace.Tracer> mockTracerInstance =
                mock(jakarta.enterprise.inject.Instance.class);
        when(mockTracerInstance.isResolvable()).thenReturn(false);

        return new ChannelGateway(
                new QhorusChannelBackend(),
                new DefaultInboundNormaliser(),
                null, // messageService — not used in these tests
                null, // channelService — not used in these tests
                channelStore,
                null, // channelInitialisedEvents — not used in these tests
                null, // channelClosedEvents — not used in these tests
                deliveryConfig,
                messageStore,
                null, // membershipService — not used in these tests
                mockTracerInstance,
                null  // tracingConfig — not used when tracerInstance.isResolvable() == false
        ) {
            @Override
            List<BackendEntry> trackedEntries(UUID channelId) {
                return entries;
            }
        };
    }
}

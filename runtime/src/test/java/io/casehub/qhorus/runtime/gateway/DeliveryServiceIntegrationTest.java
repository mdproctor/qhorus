package io.casehub.qhorus.runtime.gateway;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * End-to-end integration tests for the delivery pump. Verifies the full path:
 * dispatch → post-commit signal → pump delivers → cursor advances.
 *
 * <p>Uses {@code QuarkusTransaction.requiringNew()} (NOT {@code @TestTransaction})
 * because the post-commit signal fires via {@code TransactionSynchronizationRegistry
 * .afterCompletion(STATUS_COMMITTED)}. In a {@code @TestTransaction} test the
 * transaction rolls back, so afterCompletion fires with STATUS_ROLLEDBACK and the
 * signal is silently skipped.
 *
 * <p>Uses Awaitility because the pump runs on a background thread — delivery is
 * asynchronous relative to the dispatch call.
 *
 * <p>Each test pre-creates a {@link DeliveryCursorEntity} at {@code lastDeliveredId=0}
 * after registering the backend. This simulates cursor initialization on an empty
 * channel — the production path initializes at HEAD on first pump cycle, which
 * would skip the very first message (deliberate "start from now" policy). Pre-creating
 * the cursor at 0 lets us verify the full dispatch → pump → delivery path on the
 * first message.
 *
 * <p>Refs #132.
 */
@QuarkusTest
class DeliveryServiceIntegrationTest {

    /**
     * Minimal in-test recording backend with AT_LEAST_ONCE delivery guarantee.
     * Inline to avoid circular dependency on casehub-qhorus-testing from runtime.
     */
    static class TrackedRecordingBackend implements ChannelBackend {
        private final String id;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private volatile RuntimeException throwOnPost;

        TrackedRecordingBackend(String id) {
            this.id = id;
        }

        void throwOnNextPost(RuntimeException ex) {
            this.throwOnPost = ex;
        }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return ActorType.SYSTEM; }
        @Override public DeliveryGuarantee deliveryGuarantee() { return DeliveryGuarantee.AT_LEAST_ONCE; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            if (throwOnPost != null) {
                RuntimeException ex = throwOnPost;
                throwOnPost = null;
                throw ex;
            }
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    }

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject ChannelGateway gateway;
    @Inject DeliveryCursorStore cursorStore;
    @Inject DeliveryService deliveryService;

    @Test
    void dispatch_toTrackedBackend_deliveredByPump() {
        String channelName = "pump-e2e-deliver-" + UUID.randomUUID();
        TrackedRecordingBackend backend = new TrackedRecordingBackend("pump-rec-" + UUID.randomUUID());

        UUID channelId = createChannelCommitted(channelName);
        gateway.registerBackend(channelId, backend, "agent");
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "agent:test", MessageType.COMMAND, "Hello pump");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(backend.posts()).hasSize(1));
        assertThat(backend.posts().get(0).content()).isEqualTo("Hello pump");
        assertThat(backend.posts().get(0).type()).isEqualTo(MessageType.COMMAND);
    }

    @Test
    void dispatch_cursorAdvancedAfterDelivery() {
        String channelName = "pump-e2e-cursor-" + UUID.randomUUID();
        TrackedRecordingBackend backend = new TrackedRecordingBackend("pump-cursor-" + UUID.randomUUID());

        UUID channelId = createChannelCommitted(channelName);
        gateway.registerBackend(channelId, backend, "agent");
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "agent:cursor-test", MessageType.STATUS, "status update");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(backend.posts()).hasSize(1));

        // Cursor must exist and point past the delivered message
        Optional<DeliveryCursor> cursor = cursorStore.findByChannelAndBackend(channelId, backend.backendId());
        assertThat(cursor).isPresent();
        assertThat(cursor.get().lastDeliveredId()).isNotNull();
        assertThat(cursor.get().lastDeliveredId()).isGreaterThan(0L);
    }

    @Test
    void dispatch_multipleMessages_deliveredInOrder() {
        String channelName = "pump-e2e-order-" + UUID.randomUUID();
        TrackedRecordingBackend backend = new TrackedRecordingBackend("pump-order-" + UUID.randomUUID());

        UUID channelId = createChannelCommitted(channelName);
        gateway.registerBackend(channelId, backend, "agent");
        initializeCursorAtZero(channelId, backend.backendId());

        // Dispatch 3 messages in separate committed transactions.
        // Use types that do not require inReplyTo (COMMAND, STATUS, QUERY).
        dispatchCommitted(channelId, "agent:order", MessageType.COMMAND, "first");
        dispatchCommitted(channelId, "agent:order", MessageType.STATUS, "second");
        dispatchCommitted(channelId, "agent:order", MessageType.QUERY, "third");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(backend.posts()).hasSize(3));

        // Verify ordering matches dispatch order (message store id ASC)
        assertThat(backend.posts().get(0).content()).isEqualTo("first");
        assertThat(backend.posts().get(1).content()).isEqualTo("second");
        assertThat(backend.posts().get(2).content()).isEqualTo("third");
    }

    @Test
    void dispatch_channelDeleted_cursorsCleaned() {
        String channelName = "pump-e2e-delete-" + UUID.randomUUID();
        TrackedRecordingBackend backend = new TrackedRecordingBackend("pump-delete-" + UUID.randomUUID());

        UUID channelId = createChannelCommitted(channelName);
        gateway.registerBackend(channelId, backend, "agent");
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "agent:delete-test", MessageType.STATUS, "soon deleted");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(backend.posts()).hasSize(1));

        // Verify cursor exists before deletion
        assertThat(cursorStore.findByChannelAndBackend(channelId, backend.backendId())).isPresent();

        // Delete channel with force=true (has messages).
        // NOTE: This test manually calls cursorStore.deleteByChannel() to verify application-level
        // cleanup. It does NOT verify the DB-level ON DELETE CASCADE on fk_delivery_cursor_channel,
        // because tests use InMemoryDeliveryCursorStore (no JPA @ManyToOne FK). CASCADE verification
        // belongs in FlywayMigrationSchemaTest where Flyway migrations are applied to an actual JDBC
        // database. In production, the CASCADE removes cursors automatically; this test verifies the
        // manual cleanup path works correctly.
        QuarkusTransaction.requiringNew().run(() -> {
            cursorStore.deleteByChannel(channelId);
            channelService.delete(channelId, true);
        });

        assertThat(cursorStore.findByChannelAndBackend(channelId, backend.backendId())).isEmpty();
    }

    @Test
    void dispatch_backendFailsThenRecovers_reconcilerCatchesUp() throws InterruptedException {
        String channelName = "pump-e2e-reconcile-" + UUID.randomUUID();
        TrackedRecordingBackend backend = new TrackedRecordingBackend("pump-reconcile-" + UUID.randomUUID());

        // Configure backend to throw on the first post() call, then succeed on subsequent calls
        backend.throwOnNextPost(new RuntimeException("transient failure"));

        UUID channelId = createChannelCommitted(channelName);
        gateway.registerBackend(channelId, backend, "agent");
        initializeCursorAtZero(channelId, backend.backendId());

        // Dispatch a message — the pump will try to deliver but the backend throws
        dispatchCommitted(channelId, "agent:reconcile-test", MessageType.COMMAND, "Hello reconciler");

        // Wait briefly for the pump's first attempt to fail
        Thread.sleep(500);
        assertThat(backend.posts()).isEmpty(); // first attempt failed

        // Trigger reconciliation manually (don't wait 30s for @Scheduled)
        deliveryService.reconcileAll();

        // Wait for reconciler's delivery to succeed
        await().atMost(5, SECONDS).until(() -> !backend.posts().isEmpty());
        assertThat(backend.posts()).hasSize(1);
        assertThat(backend.posts().get(0).content()).isEqualTo("Hello reconciler");
        assertThat(backend.posts().get(0).type()).isEqualTo(MessageType.COMMAND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Creates a channel in a committed transaction. Returns the channel UUID.
     * Backend registration happens AFTER this (outside the transaction) so the
     * ChannelInitialisedEvent from create() does not interfere with test backends.
     */
    private UUID createChannelCommitted(String name) {
        UUID[] result = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ChannelCreateRequest.builder(name).build());
            result[0] = channel.id();
        });
        return result[0];
    }

    /**
     * Pre-creates a delivery cursor at {@code lastDeliveredId=0} for the given
     * channel and backend. This simulates cursor initialization on an empty channel,
     * so the pump will deliver all messages with {@code id > 0} — i.e., every message.
     *
     * <p>Without this, the pump's lazy cursor initialization sets HEAD to the current
     * last message, effectively skipping it (deliberate "start from now" policy).
     */
    private void initializeCursorAtZero(UUID channelId, String backendId) {
        QuarkusTransaction.requiringNew().run(() -> {
            cursorStore.save(io.casehub.qhorus.api.gateway.DeliveryCursor.builder()
                    .channelId(channelId).backendId(backendId)
                    .lastDeliveredId(0L).createdAt(Instant.now()).updatedAt(Instant.now())
                    .build());
        });
    }

    /**
     * Dispatches a message in a committed transaction. The post-commit signal
     * fires after the transaction commits, waking the delivery pump.
     */
    private void dispatchCommitted(UUID channelId, String sender, MessageType type, String content) {
        QuarkusTransaction.requiringNew().run(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(sender)
                        .type(type)
                        .content(content)
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.AGENT)
                        .build()));
    }
}

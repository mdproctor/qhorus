package io.casehub.qhorus.agent.bridge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.gateway.BackendRegistry;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.DeliveryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;

/**
 * End-to-end integration tests for the delivery pump with {@link AgentProviderBackend}.
 * Verifies the full async chain: dispatch → DeliverySignalQueue → pump →
 * deliverBatch → postTracked() → virtual thread → agent invocation → response dispatch.
 *
 * <p>Uses {@code QuarkusTransaction.requiringNew()} (NOT {@code @TestTransaction})
 * because the post-commit signal fires via {@code TransactionSynchronizationRegistry
 * .afterCompletion(STATUS_COMMITTED)}.
 *
 * <p>Refs #471.
 */
@QuarkusTest
class AgentBridgeDeliveryIntegrationTest {

    @Inject ChannelService channelService;
    @Inject MessageDispatcher messageDispatcher;
    @Inject BackendRegistry backendRegistry;
    @Inject DeliveryCursorStore cursorStore;
    @Inject DeliveryService deliveryService;
    @Inject MessageStore messageStore;

    @Test
    void deliveryPump_deliversCommand_agentResponds() {
        String channelName = "bridge-pump-" + UUID.randomUUID();
        String agentId = "pump-agent-" + UUID.randomUUID();
        CopyOnWriteArrayList<MessageDispatch> agentDispatches = new CopyOnWriteArrayList<>();

        UUID channelId = createChannelCommitted(channelName);

        var backend = createAndRegisterBackend(
                channelId, agentId, "LGTM", agentDispatches);
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "requester-agent", MessageType.COMMAND, "Review this code");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(agentDispatches).anyMatch(d -> d.type() == MessageType.RESPONSE));

        MessageDispatch response = agentDispatches.stream()
                .filter(d -> d.type() == MessageType.RESPONSE)
                .findFirst().orElseThrow();
        assertThat(response.content()).isEqualTo("LGTM");
        assertThat(response.sender()).isEqualTo(agentId);
    }

    @Test
    void deliveryPump_cursorAdvancesAfterDelivery() {
        String channelName = "bridge-cursor-" + UUID.randomUUID();
        String agentId = "cursor-agent-" + UUID.randomUUID();
        CopyOnWriteArrayList<MessageDispatch> agentDispatches = new CopyOnWriteArrayList<>();

        UUID channelId = createChannelCommitted(channelName);

        var backend = createAndRegisterBackend(
                channelId, agentId, "Acknowledged", agentDispatches);
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "requester", MessageType.COMMAND, "Do the thing");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(agentDispatches).anyMatch(d -> d.type() == MessageType.RESPONSE));

        Optional<DeliveryCursor> cursor = cursorStore.findByChannelAndBackend(
                channelId, backend.backendId());
        assertThat(cursor).isPresent();
        assertThat(cursor.get().lastDeliveredId()).isGreaterThan(0L);
    }

    @Test
    void deliveryPump_reconcilerCatchesMissedDelivery() {
        String channelName = "bridge-reconcile-" + UUID.randomUUID();
        String agentId = "reconcile-agent-" + UUID.randomUUID();
        CopyOnWriteArrayList<MessageDispatch> agentDispatches = new CopyOnWriteArrayList<>();

        UUID channelId = createChannelCommitted(channelName);

        // Dispatch BEFORE the backend exists — the pump signal fires but finds
        // no AT_LEAST_ONCE backend for this channel. The message sits undelivered.
        dispatchCommitted(channelId, "requester", MessageType.COMMAND, "Missed message");

        // Register the backend and initialize cursor at 0 so it sees all messages.
        // The pump may or may not have already picked this up via reconciliation —
        // either way, calling reconcileAll() guarantees delivery.
        var backend = createAndRegisterBackend(
                channelId, agentId, "Late response", agentDispatches);
        initializeCursorAtZero(channelId, backend.backendId());

        deliveryService.reconcileAll();

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(agentDispatches).anyMatch(d -> d.type() == MessageType.RESPONSE));
    }

    @Test
    void deliveryPump_skipsNonInvocationTypes() {
        String channelName = "bridge-skip-" + UUID.randomUUID();
        String agentId = "skip-agent-" + UUID.randomUUID();
        CopyOnWriteArrayList<MessageDispatch> agentDispatches = new CopyOnWriteArrayList<>();

        UUID channelId = createChannelCommitted(channelName);

        var backend = createAndRegisterBackend(
                channelId, agentId, "Should not be called", agentDispatches);
        initializeCursorAtZero(channelId, backend.backendId());

        dispatchCommitted(channelId, "other-agent", MessageType.STATUS, "Just a status update");

        await().atMost(5, SECONDS).untilAsserted(() -> {
            Optional<DeliveryCursor> cursor = cursorStore.findByChannelAndBackend(
                    channelId, backend.backendId());
            assertThat(cursor).isPresent();
            assertThat(cursor.get().lastDeliveredId()).isGreaterThan(0L);
        });

        // postTracked was called (pump delivered), but no agent invocation happened
        // because STATUS is not an invocation type
        assertThat(agentDispatches).isEmpty();
    }

    @Test
    void deliveryPump_skipsSelfSentMessages() {
        String channelName = "bridge-self-" + UUID.randomUUID();
        String agentId = "self-agent-" + UUID.randomUUID();
        CopyOnWriteArrayList<MessageDispatch> agentDispatches = new CopyOnWriteArrayList<>();

        UUID channelId = createChannelCommitted(channelName);

        var backend = createAndRegisterBackend(
                channelId, agentId, "Should not loop", agentDispatches);
        initializeCursorAtZero(channelId, backend.backendId());

        // Agent sends a message to its own channel — the loop guard should prevent invocation
        dispatchCommitted(channelId, agentId, MessageType.COMMAND, "Self-sent command");

        await().atMost(5, SECONDS).untilAsserted(() -> {
            Optional<DeliveryCursor> cursor = cursorStore.findByChannelAndBackend(
                    channelId, backend.backendId());
            assertThat(cursor).isPresent();
            assertThat(cursor.get().lastDeliveredId()).isGreaterThan(0L);
        });

        assertThat(agentDispatches).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private UUID createChannelCommitted(String name) {
        UUID[] result = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ChannelCreateRequest.builder(name).build());
            result[0] = channel.id();
        });
        return result[0];
    }

    private void initializeCursorAtZero(UUID channelId, String backendId) {
        QuarkusTransaction.requiringNew().run(() ->
                cursorStore.save(DeliveryCursor.builder()
                        .channelId(channelId).backendId(backendId)
                        .lastDeliveredId(0L).createdAt(Instant.now()).updatedAt(Instant.now())
                        .build()));
    }

    private void dispatchCommitted(UUID channelId, String sender, MessageType type, String content) {
        QuarkusTransaction.requiringNew().run(() ->
                messageDispatcher.dispatch(MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(sender)
                        .type(type)
                        .content(content)
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    private AgentProviderBackend createAndRegisterBackend(UUID channelId, String agentId,
                                                           String responseText,
                                                           CopyOnWriteArrayList<MessageDispatch> dispatches) {
        AgentBackend stubBackend = new AgentBackend() {
            @Override public String key() { return "stub-" + agentId; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().items(
                        new AgentEvent.TextDelta(responseText),
                        new AgentEvent.InvocationComplete(
                                100, 50, 0, 0, 0, null, 1000L, 900L, "s1", 1, false));
            }
            @Override public AgentSession openSession(AgentSessionInit init) { return null; }
        };

        var binding = AgentChannelBinding.builder(channelId, agentId, stubBackend.key())
                .persistent(false).maxConcurrency(3).build();

        MessageDispatcher recordingDispatcher = dispatch -> {
            dispatches.add(dispatch);
            return null;
        };

        var backend = new AgentProviderBackend(binding, stubBackend, recordingDispatcher);
        backendRegistry.registerBackend(channelId, backend, "agent");
        return backend;
    }
}

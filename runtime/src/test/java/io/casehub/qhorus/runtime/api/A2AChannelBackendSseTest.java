package io.casehub.qhorus.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;

/**
 * CDI-free unit tests for the SSE stream registry added to {@link A2AChannelBackend}.
 *
 * <p>Instantiates the backend directly — injected fields are null but none of the tested
 * methods use them. Tests cover: registration, notification, deregistration, race-free
 * deregister, null correlationId guard, and send-exception deregister.
 *
 * <p>Refs qhorus#147.
 */
class A2AChannelBackendSseTest {

    private A2AChannelBackend backend;
    private ChannelRef ref;

    @BeforeEach
    void setUp() {
        // CDI-free: injected fields (gateway, actorResolver, etc.) are null.
        // Only registry methods are exercised — they do not touch injected fields.
        backend = new A2AChannelBackend();
        ref = new ChannelRef(UUID.randomUUID(), "test-channel");
    }

    private static OutboundMessage outbound(final String correlationId, final MessageType type) {
        return new OutboundMessage(
                UUID.randomUUID(), "agent", type, "content",
                correlationId, null, ActorType.AGENT, null, null);
    }

    // ── registerStream ───────────────────────────────────────────────────────

    @Test
    void registerStream_thenPost_consumerReceivesMessage() {
        final String corrId = UUID.randomUUID().toString();
        final List<OutboundMessage> received = new ArrayList<>();
        backend.registerStream(corrId, received::add);

        backend.post(ref, outbound(corrId, MessageType.STATUS));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo(MessageType.STATUS);
    }

    @Test
    void registerStream_multipleConsumers_allReceiveMessage() {
        final String corrId = UUID.randomUUID().toString();
        final AtomicInteger count = new AtomicInteger();
        backend.registerStream(corrId, msg -> count.incrementAndGet());
        backend.registerStream(corrId, msg -> count.incrementAndGet());
        backend.registerStream(corrId, msg -> count.incrementAndGet());

        backend.post(ref, outbound(corrId, MessageType.STATUS));

        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    void streamCount_afterRegister_returnsCorrectCount() {
        final String corrId = UUID.randomUUID().toString();
        assertThat(backend.streamCount(corrId)).isEqualTo(0);

        backend.registerStream(corrId, msg -> {});
        assertThat(backend.streamCount(corrId)).isEqualTo(1);

        backend.registerStream(corrId, msg -> {});
        assertThat(backend.streamCount(corrId)).isEqualTo(2);
    }

    // ── deregisterStream ─────────────────────────────────────────────────────

    @Test
    void deregisterStream_removesConsumer_noLongerReceivesMessages() {
        final String corrId = UUID.randomUUID().toString();
        final List<OutboundMessage> received = new ArrayList<>();
        final Consumer<OutboundMessage> consumer = received::add;

        backend.registerStream(corrId, consumer);
        backend.deregisterStream(corrId, consumer);

        backend.post(ref, outbound(corrId, MessageType.STATUS));

        assertThat(received).isEmpty();
    }

    @Test
    void deregisterStream_lastConsumer_removesCorrelationEntry() {
        final String corrId = UUID.randomUUID().toString();
        final Consumer<OutboundMessage> consumer = msg -> {};

        backend.registerStream(corrId, consumer);
        assertThat(backend.streamCount(corrId)).isEqualTo(1);

        backend.deregisterStream(corrId, consumer);
        assertThat(backend.streamCount(corrId)).isEqualTo(0);
    }

    @Test
    void deregisterStream_unknownCorrelationId_isNoOp() {
        // Must not throw even if the correlationId has no registered consumers
        backend.deregisterStream(UUID.randomUUID().toString(), msg -> {});
    }

    // ── post() null correlationId guard (EVENT messages) ─────────────────────

    @Test
    void post_nullCorrelationId_ignoresAllConsumers() {
        final String corrId = UUID.randomUUID().toString();
        final AtomicInteger count = new AtomicInteger();
        backend.registerStream(corrId, msg -> count.incrementAndGet());

        // EVENT messages have null correlationId — should not reach any consumer
        final OutboundMessage eventMsg = new OutboundMessage(
                UUID.randomUUID(), "agent", MessageType.EVENT, null,
                null, null, ActorType.AGENT, null, null);
        backend.post(ref, eventMsg);

        assertThat(count.get()).isEqualTo(0);
    }

    @Test
    void post_unknownCorrelationId_ignoresAllConsumers() {
        // post() for a correlationId with no registered consumers — should not throw
        final String other = UUID.randomUUID().toString();
        backend.registerStream(UUID.randomUUID().toString(), msg -> {
            throw new AssertionError("should not be called");
        });

        backend.post(ref, outbound(other, MessageType.STATUS));
    }

    // ── resilience: one bad consumer does not block others ───────────────────

    @Test
    void post_oneConsumerThrows_otherConsumersStillReceiveMessage() {
        final String corrId = UUID.randomUUID().toString();
        final AtomicInteger goodCount = new AtomicInteger();

        backend.registerStream(corrId, msg -> { throw new RuntimeException("simulated broken pipe"); });
        backend.registerStream(corrId, msg -> goodCount.incrementAndGet());
        backend.registerStream(corrId, msg -> goodCount.incrementAndGet());

        // post() must not let one consumer's exception prevent other consumers from being called
        backend.post(ref, outbound(corrId, MessageType.STATUS));

        assertThat(goodCount.get()).isEqualTo(2);
    }
}

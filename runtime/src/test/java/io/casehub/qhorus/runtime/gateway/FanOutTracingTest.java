package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import jakarta.enterprise.inject.Instance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;

class FanOutTracingTest {

    /** Minimal local recording backend — avoids circular dependency on casehub-qhorus-testing. */
    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final DeliveryGuarantee guarantee;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());
        private volatile RuntimeException throwOnPost;

        RecordingBackend(String id, ActorType actorType, DeliveryGuarantee guarantee) {
            this.id = id;
            this.actorType = actorType;
            this.guarantee = guarantee;
        }

        void throwOnNextPost(RuntimeException ex) { this.throwOnPost = ex; }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public DeliveryGuarantee deliveryGuarantee() { return guarantee; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            RuntimeException ex = throwOnPost;
            if (ex != null) { throwOnPost = null; throw ex; }
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    }

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private ChannelGateway gateway;
    private QhorusTracingConfig tracingConfig;
    private DeliveryConfig deliveryConfig;
    private UUID channelId;
    private ChannelRef channelRef;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        // Wire gateway with InMemory dependencies — same pattern as existing ChannelGatewayTest
        tracingConfig = new QhorusTracingConfig() {
            @Override public boolean enabled() { return true; }
            @Override public boolean dispatch() { return true; }
            @Override public boolean commitments() { return true; }
            @Override public boolean fanOut() { return true; }
            @Override public boolean ledgerWrite() { return true; }
            @Override public boolean delivery() { return true; }
        };

        deliveryConfig = new DeliveryConfig() {
            @Override public boolean enabled() { return false; }
            @Override public int batchSize() { return 100; }
            @Override public int maxConsecutiveFailures() { return 10; }
            @Override public String reconciliationInterval() { return "30s"; }
        };

        QhorusChannelBackend agentBackend = new QhorusChannelBackend();
        DefaultInboundNormaliser normaliser = new DefaultInboundNormaliser();

        @SuppressWarnings("unchecked")
        Instance<io.opentelemetry.api.trace.Tracer> mockInstance = mock(Instance.class);
        when(mockInstance.isResolvable()).thenReturn(false);

        @SuppressWarnings("unchecked")
        jakarta.enterprise.event.Event<io.casehub.qhorus.api.gateway.ChannelInitialisedEvent> mockEvent = mock(jakarta.enterprise.event.Event.class);

        gateway = new ChannelGateway(
                agentBackend,
                normaliser,
                null, // messageService not needed for fanOut tests
                null, // channelService not needed for fanOut tests
                null, // crossTenantChannelStore not needed for fanOut tests
                mockEvent,
                null, // channelClosedEvents not needed for fanOut tests
                deliveryConfig,
                null, // crossTenantMessageStore not needed for fanOut tests
                null, // membershipService not needed for fanOut tests
                mockInstance,
                tracingConfig);

        gateway.tracerInstance = () -> provider.get("qhorus-test");

        channelId = UUID.randomUUID();
        channelRef = new ChannelRef(channelId, "test-channel");
        gateway.initChannel(channelId, channelRef);
    }

    @Test
    void fanOut_creates_span_with_backend_count() {
        // Arrange: register a BEST_EFFORT backend
        RecordingBackend backend = new RecordingBackend("test-backend", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        gateway.registerBackend(channelId, backend, "agent");

        OutboundMessage outboundMessage = new OutboundMessage(
                UUID.randomUUID(),
                "test-sender",
                MessageType.STATUS,
                "test content",
                null,
                null,
                ActorType.AGENT, null, null);

        // Act
        boolean hasTracked = gateway.fanOut(channelId, "test-channel", outboundMessage);

        // Wait for virtual threads to complete
        await().atMost(2, TimeUnit.SECONDS).until(() -> backend.posts().size() == 1);

        // Assert
        assertThat(hasTracked).isFalse();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSizeGreaterThanOrEqualTo(1);

        SpanData parentSpan = spans.stream()
                .filter(s -> s.getName().equals("qhorus.fanout"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No qhorus.fanout span found"));

        assertThat(parentSpan.getAttributes().asMap())
                .containsEntry(AttributeKey.stringKey("qhorus.channel.id"), channelId.toString())
                .containsEntry(AttributeKey.longKey("qhorus.fanout.backend_count"), 1L)
                .containsEntry(AttributeKey.booleanKey("qhorus.fanout.has_tracked"), false);
    }

    @Test
    void fanOut_creates_child_span_per_backend() {
        // Arrange: register two BEST_EFFORT backends
        RecordingBackend backend1 = new RecordingBackend("backend-1", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        RecordingBackend backend2 = new RecordingBackend("backend-2", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        gateway.registerBackend(channelId, backend1, "agent");
        gateway.registerBackend(channelId, backend2, "agent");

        OutboundMessage outboundMessage = new OutboundMessage(
                UUID.randomUUID(),
                "test-sender",
                MessageType.QUERY,
                "query content",
                "test-corr-id",
                null,
                ActorType.AGENT, null, null);

        // Act
        gateway.fanOut(channelId, "test-channel", outboundMessage);

        // Wait for virtual threads to complete
        await().atMost(2, TimeUnit.SECONDS).until(() ->
                backend1.posts().size() + backend2.posts().size() == 2);

        // Assert: two "qhorus.fanout.backend" child spans with backend_id attributes
        List<SpanData> spans = exporter.getFinishedSpanItems();
        List<SpanData> childSpans = spans.stream()
                .filter(s -> s.getName().equals("qhorus.fanout.backend"))
                .toList();

        assertThat(childSpans).hasSize(2);

        List<String> backendIds = childSpans.stream()
                .map(s -> s.getAttributes().get(AttributeKey.stringKey("qhorus.fanout.backend_id")))
                .toList();
        assertThat(backendIds).containsExactlyInAnyOrder("backend-1", "backend-2");

        // Verify delivery_guarantee attribute
        childSpans.forEach(span ->
                assertThat(span.getAttributes().get(AttributeKey.stringKey("qhorus.fanout.delivery_guarantee")))
                        .isEqualTo("BEST_EFFORT"));
    }

    @Test
    void fanOut_skips_AT_LEAST_ONCE_backends_when_delivery_enabled() {
        // Arrange: recreate gateway with delivery enabled
        DeliveryConfig enabledDeliveryConfig = new DeliveryConfig() {
            @Override public boolean enabled() { return true; }
            @Override public int batchSize() { return 100; }
            @Override public int maxConsecutiveFailures() { return 10; }
            @Override public String reconciliationInterval() { return "30s"; }
        };

        QhorusChannelBackend agentBackend = new QhorusChannelBackend();
        DefaultInboundNormaliser normaliser = new DefaultInboundNormaliser();

        @SuppressWarnings("unchecked")
        Instance<io.opentelemetry.api.trace.Tracer> mockInstance = mock(Instance.class);
        when(mockInstance.isResolvable()).thenReturn(false);

        @SuppressWarnings("unchecked")
        jakarta.enterprise.event.Event<io.casehub.qhorus.api.gateway.ChannelInitialisedEvent> mockEvent2 = mock(jakarta.enterprise.event.Event.class);

        ChannelGateway gatewayWithDelivery = new ChannelGateway(
                agentBackend,
                normaliser,
                null,
                null,
                null,
                mockEvent2,
                null, // channelClosedEvents
                enabledDeliveryConfig,
                null,
                null, // membershipService
                mockInstance,
                tracingConfig);
        gatewayWithDelivery.tracerInstance = () -> provider.get("qhorus-test");
        gatewayWithDelivery.initChannel(channelId, channelRef);

        RecordingBackend bestEffortBackend = new RecordingBackend("best-effort", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        RecordingBackend trackedBackend = new RecordingBackend("tracked", ActorType.AGENT, DeliveryGuarantee.AT_LEAST_ONCE);
        gatewayWithDelivery.registerBackend(channelId, bestEffortBackend, "agent");
        gatewayWithDelivery.registerBackend(channelId, trackedBackend, "agent");

        OutboundMessage outboundMessage = new OutboundMessage(
                UUID.randomUUID(),
                "test-sender",
                MessageType.STATUS,
                "test",
                null,
                null,
                ActorType.AGENT, null, null);

        // Act
        boolean hasTracked = gatewayWithDelivery.fanOut(channelId, "test-channel", outboundMessage);

        // Wait for virtual threads
        await().atMost(2, TimeUnit.SECONDS).until(() -> bestEffortBackend.posts().size() == 1);

        // Assert: hasTracked is true, only BEST_EFFORT backend has child span
        assertThat(hasTracked).isTrue();
        assertThat(trackedBackend.posts()).isEmpty();
        assertThat(bestEffortBackend.posts()).hasSize(1);

        List<SpanData> childSpans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("qhorus.fanout.backend"))
                .toList();

        // Only one child span for BEST_EFFORT backend
        assertThat(childSpans).hasSize(1);
        assertThat(childSpans.get(0).getAttributes().get(AttributeKey.stringKey("qhorus.fanout.backend_id")))
                .isEqualTo("best-effort");
    }

    @Test
    void fanOut_child_span_records_error_on_backend_failure() {
        // Arrange
        RecordingBackend backend = new RecordingBackend("failing-backend", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        backend.throwOnNextPost(new RuntimeException("Backend post failed"));
        gateway.registerBackend(channelId, backend, "agent");

        OutboundMessage outboundMessage = new OutboundMessage(
                UUID.randomUUID(),
                "test-sender",
                MessageType.STATUS,
                "test",
                null,
                null,
                ActorType.AGENT, null, null);

        // Act
        gateway.fanOut(channelId, "test-channel", outboundMessage);

        // Wait for virtual thread to complete (will fail but span should be recorded)
        await().atMost(2, TimeUnit.SECONDS).until(() -> !exporter.getFinishedSpanItems().isEmpty());

        // Assert: child span has ERROR status and recorded exception
        List<SpanData> childSpans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("qhorus.fanout.backend"))
                .toList();

        assertThat(childSpans).hasSize(1);
        SpanData errorSpan = childSpans.get(0);
        assertThat(errorSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(errorSpan.getEvents()).isNotEmpty();
        assertThat(errorSpan.getEvents().get(0).getName()).isEqualTo("exception");
    }

    @Test
    void fanOut_disabled_tracing_creates_no_spans() {
        // Arrange: disable tracing
        tracingConfig = new QhorusTracingConfig() {
            @Override public boolean enabled() { return false; }
            @Override public boolean dispatch() { return true; }
            @Override public boolean commitments() { return true; }
            @Override public boolean fanOut() { return true; }
            @Override public boolean ledgerWrite() { return true; }
            @Override public boolean delivery() { return true; }
        };
        gateway.tracingConfig = tracingConfig;

        RecordingBackend backend = new RecordingBackend("test-backend", ActorType.AGENT, DeliveryGuarantee.BEST_EFFORT);
        gateway.registerBackend(channelId, backend, "agent");

        OutboundMessage outboundMessage = new OutboundMessage(
                UUID.randomUUID(),
                "test-sender",
                MessageType.STATUS,
                "test",
                null,
                null,
                ActorType.AGENT, null, null);

        // Act
        gateway.fanOut(channelId, "test-channel", outboundMessage);

        // Wait for virtual thread
        await().atMost(2, TimeUnit.SECONDS).until(() -> backend.posts().size() == 1);

        // Assert: no spans created
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}

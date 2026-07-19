package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;

import io.opentelemetry.api.trace.Tracer;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;

/**
 * CDI-free tests for fanOut() delivery guarantee routing.
 *
 * <p>Verifies that fanOut() correctly routes messages based on backend delivery guarantee
 * and whether the delivery pump is enabled:
 * <ul>
 *   <li>BEST_EFFORT backends always receive direct post() calls</li>
 *   <li>AT_LEAST_ONCE backends are skipped when the pump is enabled</li>
 *   <li>AT_LEAST_ONCE backends receive direct delivery when the pump is disabled (safe fallback R3-02)</li>
 * </ul>
 */
class FanOutDeliveryGuaranteeTest {

    /** Recording backend that declares a configurable delivery guarantee. */
    static class GuaranteeRecordingBackend implements ChannelBackend {
        private final String id;
        private final DeliveryGuarantee guarantee;
        private final List<OutboundMessage> posts = Collections.synchronizedList(new ArrayList<>());

        GuaranteeRecordingBackend(String id, DeliveryGuarantee guarantee) {
            this.id = id;
            this.guarantee = guarantee;
        }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return ActorType.AGENT; }
        @Override public DeliveryGuarantee deliveryGuarantee() { return guarantee; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) { posts.add(message); }
        @Override public void close(ChannelRef channel) {}

        List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    }

    DeliveryConfig deliveryConfig;
    ChannelGateway gateway;
    UUID channelId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        deliveryConfig = mock(DeliveryConfig.class);
        when(deliveryConfig.enabled()).thenReturn(true);
        QhorusChannelBackend agentBackend = new QhorusChannelBackend();
        gateway = new ChannelGateway(agentBackend, new DefaultInboundNormaliser(),
                mock(MessageService.class), mock(ChannelService.class),
                mock(CrossTenantChannelStore.class), mock(Event.class), mock(Event.class),
                deliveryConfig, mock(io.casehub.qhorus.api.store.CrossTenantMessageStore.class),
                null, mock(Instance.class), mock(QhorusTracingConfig.class));
        channelId = UUID.randomUUID();
        gateway.initChannel(channelId, new ChannelRef(channelId, "test-channel"));
    }

    private OutboundMessage testMessage() {
        return new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.COMMAND, "do it", null, null, ActorType.AGENT, null, null);
    }

    @Test
    void fanOut_bestEffortBackend_deliveredDirectly() throws Exception {
        GuaranteeRecordingBackend backend = new GuaranteeRecordingBackend("best-effort-1", DeliveryGuarantee.BEST_EFFORT);
        gateway.registerBackend(channelId, backend, "agent");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());
        Thread.sleep(200);

        assertThat(hasTracked).isFalse();
        assertThat(backend.posts()).hasSize(1);
    }

    @Test
    void fanOut_atLeastOnceBackend_skippedWhenEnabled() throws Exception {
        GuaranteeRecordingBackend backend = new GuaranteeRecordingBackend("tracked-1", DeliveryGuarantee.AT_LEAST_ONCE);
        gateway.registerBackend(channelId, backend, "human_participating");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());
        Thread.sleep(200);

        assertThat(hasTracked).isTrue();
        assertThat(backend.posts()).isEmpty();
    }

    @Test
    void fanOut_atLeastOnceBackend_deliveredWhenDisabled() throws Exception {
        when(deliveryConfig.enabled()).thenReturn(false);
        GuaranteeRecordingBackend backend = new GuaranteeRecordingBackend("tracked-1", DeliveryGuarantee.AT_LEAST_ONCE);
        gateway.registerBackend(channelId, backend, "human_participating");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());
        Thread.sleep(200);

        assertThat(hasTracked).isFalse();
        assertThat(backend.posts()).hasSize(1);
    }

    @Test
    void fanOut_mixedBackends_correctRouting() throws Exception {
        GuaranteeRecordingBackend bestEffort = new GuaranteeRecordingBackend("best-effort-1", DeliveryGuarantee.BEST_EFFORT);
        GuaranteeRecordingBackend tracked = new GuaranteeRecordingBackend("tracked-1", DeliveryGuarantee.AT_LEAST_ONCE);
        gateway.registerBackend(channelId, bestEffort, "agent");
        gateway.registerBackend(channelId, tracked, "human_participating");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());
        Thread.sleep(200);

        assertThat(hasTracked).isTrue();
        assertThat(bestEffort.posts()).hasSize(1);
        assertThat(tracked.posts()).isEmpty();
    }

    @Test
    void fanOut_noTrackedBackends_returnsFalse() {
        GuaranteeRecordingBackend be1 = new GuaranteeRecordingBackend("be-1", DeliveryGuarantee.BEST_EFFORT);
        GuaranteeRecordingBackend be2 = new GuaranteeRecordingBackend("be-2", DeliveryGuarantee.BEST_EFFORT);
        gateway.registerBackend(channelId, be1, "agent");
        gateway.registerBackend(channelId, be2, "agent");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());

        assertThat(hasTracked).isFalse();
    }

    @Test
    void fanOut_hasTrackedBackends_returnsTrue() {
        GuaranteeRecordingBackend tracked = new GuaranteeRecordingBackend("tracked-1", DeliveryGuarantee.AT_LEAST_ONCE);
        gateway.registerBackend(channelId, tracked, "human_participating");

        boolean hasTracked = gateway.fanOut(channelId, "test-channel", testMessage());

        assertThat(hasTracked).isTrue();
    }
}

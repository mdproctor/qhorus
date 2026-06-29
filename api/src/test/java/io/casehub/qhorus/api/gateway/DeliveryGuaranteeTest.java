package io.casehub.qhorus.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class DeliveryGuaranteeTest {

    @Test
    void enumHasTwoValues() {
        assertThat(DeliveryGuarantee.values())
                .containsExactly(DeliveryGuarantee.BEST_EFFORT, DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void channelBackendDefaultIsBestEffort() {
        ChannelBackend stub = new ChannelBackend() {
            @Override public String backendId() { return "stub"; }
            @Override public io.casehub.platform.api.identity.ActorType actorType() {
                return io.casehub.platform.api.identity.ActorType.AGENT;
            }
            @Override public void open(ChannelRef channel, java.util.Map<String, String> metadata) {}
            @Override public void post(ChannelRef channel, OutboundMessage message) {}
            @Override public void close(ChannelRef channel) {}
        };
        assertThat(stub.deliveryGuarantee()).isEqualTo(DeliveryGuarantee.BEST_EFFORT);
    }
}

package io.casehub.qhorus.runtime.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelBindingUpdateTest {

    @Inject
    ChannelService channelService;

    @Inject
    ChannelBindingStore channelBindingStore;

    private Channel channelWithBinding(String suffix) {
        return channelService.create(io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("binding-ch-" + suffix)
                                                                                       .description("desc")
                                                                                       .inboundConnectorId("twilio").externalKey("+44" + suffix)
                                                                                       .outboundConnectorId("twilio-out").outboundDestination("+44" + suffix)
                                                                                       .build());
    }

    @Test
    @TestTransaction
    void updateConnectorBinding_updatesOutboundFields() {
        Channel ch = channelWithBinding(UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        channelService.updateConnectorBinding(ch.id(), "vonage-out", "+447999888777");

        ChannelConnectorBinding updated = channelBindingStore.findByChannelId(ch.id()).orElseThrow();
        assertEquals("vonage-out", updated.outboundConnectorId());
        assertEquals("+447999888777", updated.outboundDestination());
    }

    @Test
    @TestTransaction
    void updateConnectorBinding_throwsWhenChannelNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                channelService.updateConnectorBinding(UUID.randomUUID(), "out", "dest"));
    }

    @Test
    @TestTransaction
    void updateConnectorBinding_throwsWhenNoBinding() {
        Channel ch = channelService.create(io.casehub.qhorus.api.channel.ChannelCreateRequest.builder(
                "no-binding-ch-" + UUID.randomUUID()).build());
        assertThrows(IllegalStateException.class, () ->
                channelService.updateConnectorBinding(ch.id(), "out", "dest"));
    }
}

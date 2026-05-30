package io.casehub.qhorus.runtime.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for ChannelService.updateConnectorBinding() — state verification.
 * Event firing is verified in ChannelBindingUpdateEventTest (pure unit test).
 * Refs #215
 */
@QuarkusTest
class ChannelBindingUpdateTest {

    @Inject
    ChannelService channelService;

    @Inject
    ChannelBindingStore channelBindingStore;

    private Channel channelWithBinding(String suffix) {
        return channelService.create(new ChannelCreateRequest(
                "binding-ch-" + suffix, "desc", ChannelSemantic.APPEND,
                null, null, null, null, null, null,
                "twilio", "+44" + suffix, "twilio-out", "+44" + suffix));
    }

    @Test
    @TestTransaction
    void updateConnectorBinding_updatesOutboundFields() {
        Channel ch = channelWithBinding(UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        channelService.updateConnectorBinding(ch.id, "vonage-out", "+447999888777");

        ChannelConnectorBinding updated = channelBindingStore.findByChannelId(ch.id).orElseThrow();
        assertEquals("vonage-out", updated.outboundConnectorId);
        assertEquals("+447999888777", updated.outboundDestination);
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
        Channel ch = channelService.create(ChannelCreateRequest.simple(
                "no-binding-ch-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        assertThrows(IllegalStateException.class, () ->
                channelService.updateConnectorBinding(ch.id, "out", "dest"));
    }
}

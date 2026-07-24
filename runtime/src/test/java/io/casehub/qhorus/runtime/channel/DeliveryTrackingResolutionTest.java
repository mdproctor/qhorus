package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTrackingResolutionTest {

    @Test
    void barrier_defaultsToEnabled() {
        Channel ch = Channel.builder("barrier-ch").semantic(ChannelSemantic.BARRIER).build();
        assertTrue(ChannelService.isDeliveryTrackingEnabled(ch));
    }

    @Test
    void collect_defaultsToEnabled() {
        Channel ch = Channel.builder("collect-ch").semantic(ChannelSemantic.COLLECT).build();
        assertTrue(ChannelService.isDeliveryTrackingEnabled(ch));
    }

    @ParameterizedTest
    @EnumSource(value = ChannelSemantic.class, names = {"APPEND", "EPHEMERAL", "LAST_WRITE"})
    void nonCoordination_defaultsToDisabled(ChannelSemantic semantic) {
        Channel ch = Channel.builder("ch").semantic(semantic).build();
        assertFalse(ChannelService.isDeliveryTrackingEnabled(ch));
    }

    @Test
    void explicitTrue_overridesDefault() {
        Channel ch = Channel.builder("ch").semantic(ChannelSemantic.APPEND).trackDelivery(true).build();
        assertTrue(ChannelService.isDeliveryTrackingEnabled(ch));
    }

    @Test
    void explicitFalse_overridesDefault() {
        Channel ch = Channel.builder("ch").semantic(ChannelSemantic.BARRIER).trackDelivery(false).build();
        assertFalse(ChannelService.isDeliveryTrackingEnabled(ch));
    }

    @Test
    void nullTrackDelivery_usesSemanticDefault() {
        Channel ch = Channel.builder("ch").semantic(ChannelSemantic.BARRIER).trackDelivery(null).build();
        assertTrue(ChannelService.isDeliveryTrackingEnabled(ch));
    }
}

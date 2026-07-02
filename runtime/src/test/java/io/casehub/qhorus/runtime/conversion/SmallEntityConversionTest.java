package io.casehub.qhorus.runtime.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SmallEntityConversionTest {

    @Test
    void instance_roundTrip() {
        var original = io.casehub.qhorus.api.instance.Instance.builder("agent-1")
                .id(UUID.randomUUID()).status("online").description("test")
                .lastSeen(Instant.now()).registeredAt(Instant.now()).build();
        var roundTripped = io.casehub.qhorus.runtime.instance.InstanceEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void sharedData_roundTrip() {
        var original = io.casehub.qhorus.api.data.SharedData.builder("key-1")
                .id(UUID.randomUUID()).content("hello").createdBy("agent").complete(true)
                .sizeBytes(5).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        var roundTripped = io.casehub.qhorus.runtime.data.SharedDataEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void artefactClaim_roundTrip() {
        var original = new io.casehub.qhorus.api.data.ArtefactClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        var roundTripped = io.casehub.qhorus.runtime.data.ArtefactClaimEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void watchdog_roundTrip() {
        var original = io.casehub.qhorus.api.watchdog.Watchdog.builder("BARRIER_STUCK", "ch-1")
                .id(UUID.randomUUID()).thresholdSeconds(60).notificationChannel("alerts")
                .createdBy("admin").tenancyId("t1").createdAt(Instant.now()).build();
        var roundTripped = io.casehub.qhorus.runtime.watchdog.WatchdogEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void deliveryCursor_roundTrip() {
        var original = new io.casehub.qhorus.api.gateway.DeliveryCursor(
                UUID.randomUUID(), UUID.randomUUID(), "backend-1", 100L, 3, Instant.now(), Instant.now());
        var roundTripped = io.casehub.qhorus.runtime.gateway.DeliveryCursorEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void channelConnectorBinding_roundTrip() {
        var original = new io.casehub.qhorus.api.channel.ChannelConnectorBinding(
                UUID.randomUUID(), "slack", "C123", "slack-out", "#general");
        var roundTripped = io.casehub.qhorus.runtime.channel.ChannelConnectorBindingEntity.fromDomain(original).toDomain();
        assertThat(roundTripped).isEqualTo(original);
    }
}

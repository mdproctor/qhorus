package io.casehub.qhorus.api;

import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Test imports for all 6 records

class DomainRecordTest {

    @Test void instance_builder() {
        Instance i = Instance.builder("agent-1").id(UUID.randomUUID()).status("online").build();
        assertThat(i.instanceId()).isEqualTo("agent-1");
        assertThat(i.status()).isEqualTo("online");
    }

    @Test void sharedData_builder() {
        SharedData d = SharedData.builder("my-key").content("hello").complete(true).sizeBytes(5).build();
        assertThat(d.key()).isEqualTo("my-key");
        assertThat(d.content()).isEqualTo("hello");
        assertThat(d.complete()).isTrue();
    }

    @Test void artefactClaim_canonical() {
        UUID id = UUID.randomUUID();
        ArtefactClaim c = new ArtefactClaim(id, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        assertThat(c.id()).isEqualTo(id);
    }

    @Test void watchdog_builder() {
        Watchdog w = Watchdog.builder(WatchdogConditionType.BARRIER_STUCK, "my-channel")
                .thresholdSeconds(60).notificationChannel("alerts").build();
        assertThat(w.conditionType()).isEqualTo(WatchdogConditionType.BARRIER_STUCK);
        assertThat(w.targetName()).isEqualTo("my-channel");
        assertThat(w.thresholdSeconds()).isEqualTo(60);
    }

    @Test void deliveryCursor_canonical() {
        DeliveryCursor dc = new DeliveryCursor(UUID.randomUUID(), UUID.randomUUID(), "backend-1", 100L, 3, Instant.now(), Instant.now());
        assertThat(dc.backendId()).isEqualTo("backend-1");
        assertThat(dc.lastDeliveredVersion()).isEqualTo(3);
    }

    @Test void channelConnectorBinding_canonical() {
        ChannelConnectorBinding b = new ChannelConnectorBinding(UUID.randomUUID(), "slack", "C123", "slack-out", "#general");
        assertThat(b.inboundConnectorId()).isEqualTo("slack");
        assertThat(b.externalKey()).isEqualTo("C123");
    }
}

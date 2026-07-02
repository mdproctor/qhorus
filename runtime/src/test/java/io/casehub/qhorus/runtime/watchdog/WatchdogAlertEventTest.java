package io.casehub.qhorus.runtime.watchdog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.casehub.qhorus.api.WatchdogEnabledProfile;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.watchdog.BarrierStuckContext;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(WatchdogEnabledProfile.class)
class WatchdogAlertEventTest {

    @Inject
    WatchdogEvaluationService service;

    @Inject
    ChannelStore channelStore;

    @Inject
    WatchdogStore watchdogStore;

    @BeforeEach
    void resetCapture() {
        AlertEventCapture.expectCount(0);
    }

    @Test
    @Transactional
    void barrierStuck_firesWatchdogAlertEvent() throws InterruptedException {
        UUID chId = UUID.randomUUID();
        Channel ch = channelStore.put(Channel.builder("barrier-test-" + chId)
                .id(chId).semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-alpha", "agent-beta"))
                .lastActivityAt(Instant.now().minusSeconds(3600))
                .build());

        Watchdog w = watchdogStore.put(Watchdog.builder("BARRIER_STUCK", ch.name())
                .thresholdSeconds(0)
                .notificationChannel("alerts-" + UUID.randomUUID())
                .createdBy("test").build());

        AlertEventCapture.expectCount(1);
        service.evaluateAll();

        assertTrue(AlertEventCapture.await(2, TimeUnit.SECONDS), "WatchdogAlertEvent not delivered within 2s");
        assertThat(AlertEventCapture.events).hasSize(1);

        var event = AlertEventCapture.events.get(0);
        assertThat(event.conditionType()).isEqualTo(WatchdogConditionType.BARRIER_STUCK);
        assertThat(event.watchdogId()).isEqualTo(w.id());
        assertThat(event.targetName()).isEqualTo(ch.name());

        BarrierStuckContext ctx = (BarrierStuckContext) event.context();
        assertThat(ctx.channelId()).isEqualTo(ch.id());
        assertThat(ctx.channelName()).isEqualTo(ch.name());
        assertThat(ctx.missingContributors()).containsExactlyInAnyOrder("agent-alpha", "agent-beta");
        assertThat(ctx.elapsedSeconds()).isGreaterThan(3500L);
    }
}

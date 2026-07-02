package io.casehub.qhorus.runtime.watchdog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.casehub.qhorus.api.WatchdogAlertEndpointsProfile;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.WatchdogStore;
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
@TestProfile(WatchdogAlertEndpointsProfile.class)
class WatchdogAlertE2ETest {

    @Inject
    WatchdogEvaluationService service;

    @Inject
    ChannelStore channelStore;

    @Inject
    WatchdogStore watchdogStore;

    @BeforeEach
    void reset() {
        TestSlackConnectorE2E.clear();
        AlertEventCapture.expectCount(1);
    }

    @Test
    @Transactional
    void barrierStuck_eventFlowsToConnector() throws InterruptedException {
        UUID chId = UUID.randomUUID();
        Channel ch = channelStore.put(Channel.builder("e2e-barrier-" + chId)
                .id(chId).semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-x"))
                .lastActivityAt(Instant.now().minusSeconds(3600))
                .build());

        watchdogStore.put(Watchdog.builder("BARRIER_STUCK", ch.name())
                .thresholdSeconds(0)
                .notificationChannel("e2e-alerts-" + UUID.randomUUID())
                .createdBy("test").build());

        service.evaluateAll();

        assertTrue(AlertEventCapture.await(2, TimeUnit.SECONDS),
                "WatchdogAlertEvent not delivered within 2s");

        assertThat(AlertEventCapture.events.get(0).conditionType())
                .isEqualTo(WatchdogConditionType.BARRIER_STUCK);

        assertThat(TestSlackConnectorE2E.sent).hasSize(1);
        var msg = TestSlackConnectorE2E.sent.get(0);
        assertThat(msg.destination()).isEqualTo("https://hooks.slack.com/test");
        assertThat(msg.title()).startsWith("[Qhorus Alert] BARRIER_STUCK:");
        assertThat(msg.body()).contains("agent-x");
    }
}

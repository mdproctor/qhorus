package io.casehub.qhorus.runtime.watchdog;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Verifies that WatchdogEvaluationService condition evaluation works correctly
 * through the store seam. Each test uses unique channel names to avoid cross-test
 * pollution; @TestTransaction rolls back JPA writes between tests.
 *
 * <p>Refs #205, #207, #209
 */
@QuarkusTest
@TestProfile(io.casehub.qhorus.api.WatchdogEnabledProfile.class)
class WatchdogEvaluationServiceTest {

    @Inject
    WatchdogEvaluationService watchdogService;

    @Inject
    ChannelStore channelStore;

    @Inject
    WatchdogStore watchdogStore;

    @Inject
    CommitmentStore commitmentStore;

    @Inject
    InstanceStore instanceStore;

    @Inject
    MessageStore messageStore;

    // -------------------------------------------------------------------------
    // evaluateApprovalPending — CommitmentStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateApprovalPending_firesAlert_whenOpenCommitmentWithExpiryExists() {
        Channel notifCh = channelStore.put(Channel.builder("notif-approval-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("APPROVAL_PENDING", "*")
                .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder().state(CommitmentState.OPEN).expiresAt(Instant.now().plusSeconds(30)).channelId(UUID.randomUUID()).correlationId(UUID.randomUUID().toString()).messageType(MessageType.QUERY).obligor("agent-a").requester("agent-b").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(),
                "APPROVAL_PENDING should trigger when open commitment has expiresAt set and threshold=0");
        assertTrue(alerts.get(0).content().contains("APPROVAL_PENDING"),
                "Alert content should identify the APPROVAL_PENDING condition type");
    }

    @Test
    @TestTransaction
    void evaluateApprovalPending_noAlert_whenOpenCommitmentHasNoExpiry() {
        Channel notifCh = channelStore.put(Channel.builder("notif-approval-no-expiry-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("APPROVAL_PENDING", "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder().state(CommitmentState.OPEN).expiresAt(null).channelId(UUID.randomUUID()).correlationId(UUID.randomUUID().toString()).messageType(MessageType.QUERY).obligor("agent-a").requester("agent-b").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "APPROVAL_PENDING must not fire when commitment has no expiresAt — the expiresAt filter must exclude it");
    }

    // -------------------------------------------------------------------------
    // evaluateAgentStale — InstanceStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateAgentStale_firesAlert_whenStaleInstanceExists() {
        Channel notifCh = channelStore.put(Channel.builder("notif-stale-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("AGENT_STALE", "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        instanceStore.put(Instance.builder("stale-agent-" + UUID.randomUUID()).status("stale").lastSeen(Instant.now().minusSeconds(10)).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(),
                "AGENT_STALE should trigger when an instance with status='stale' exists and threshold=0");
        assertTrue(alerts.get(0).content().contains("AGENT_STALE"),
                "Alert content should identify the AGENT_STALE condition type");
    }

    @Test
    @TestTransaction
    void evaluateAgentStale_noAlert_whenInstanceIsNotStale() {
        Channel notifCh = channelStore.put(Channel.builder("notif-stale-online-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("AGENT_STALE", "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        // Instance exists but is online — InstanceQuery.status("stale") filter must exclude it
        instanceStore.put(Instance.builder("online-agent-" + UUID.randomUUID()).status("online").lastSeen(Instant.now().minusSeconds(10)).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "AGENT_STALE must not fire when the only instance has status='online' — the status filter must exclude it");
    }

    // -------------------------------------------------------------------------
    // evaluateQueueDepth — MessageStore.count() seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateQueueDepth_firesAlert_whenNonEventMessageCountExceedsThreshold() {
        // evaluateQueueDepth calls channelService.listAll() — the queue channel
        // must be in the store so channelService.listAll() returns it.
        Channel queueCh = channelStore.put(Channel.builder("queue-ch-" + UUID.randomUUID()).semantic(ChannelSemantic.COLLECT).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-queue-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("QUEUE_DEPTH", queueCh.name()).thresholdCount(2).notificationChannel(notifCh.name()).createdBy("test").build());

        // 3 non-EVENT messages — exceeds threshold of 2
        for (int i = 0; i < 3; i++) {
            messageStore.put(Message.builder().channelId(queueCh.id()).sender("agent-" + i).messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("work item " + i).build());
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(),
                "QUEUE_DEPTH should trigger when non-EVENT message count (3) exceeds threshold (2)");
        assertTrue(alerts.get(0).content().contains("QUEUE_DEPTH"),
                "Alert content should identify the QUEUE_DEPTH condition type");
    }

    @Test
    @TestTransaction
    void evaluateQueueDepth_noAlert_whenBelowThreshold() {
        Channel queueCh = channelStore.put(Channel.builder("queue-below-" + UUID.randomUUID()).semantic(ChannelSemantic.COLLECT).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-queue-below-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("QUEUE_DEPTH", queueCh.name()).thresholdCount(5).notificationChannel(notifCh.name()).createdBy("test").build());

        // 2 messages — below threshold of 5
        for (int i = 0; i < 2; i++) {
            messageStore.put(Message.builder().channelId(queueCh.id()).sender("agent-" + i).messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("work item " + i).build());
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "QUEUE_DEPTH must not fire when message count (2) is below threshold (5)");
    }

    // -------------------------------------------------------------------------
    // Debounce — verifies lastFiredAt persistence prevents re-fire (#207)
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateAll_debounce_preventsRefireWithinWindow() {
        Channel queueCh = channelStore.put(Channel.builder("queue-debounce-" + UUID.randomUUID()).semantic(ChannelSemantic.COLLECT).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-debounce-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("QUEUE_DEPTH", queueCh.name()).thresholdCount(2).thresholdSeconds(300).notificationChannel(notifCh.name()).createdBy("test").build());

        // 3 messages — condition is met on both calls
        for (int i = 0; i < 3; i++) {
            messageStore.put(Message.builder().channelId(queueCh.id()).sender("agent-" + i).messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("work item " + i).build());
        }

        // First evaluation — condition met, alert fires, lastFiredAt is stamped
        watchdogService.evaluateAll();
        List<Message> alertsAfterFirst = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertEquals(1, alertsAfterFirst.size(),
                "First evaluateAll() should produce exactly one alert when condition is met");

        // Second evaluation — condition still met, but debounce window (300s) has not expired
        watchdogService.evaluateAll();
        List<Message> alertsAfterSecond = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertEquals(1, alertsAfterSecond.size(),
                "Second evaluateAll() within the debounce window must not produce a second alert — isDebounced() must see the lastFiredAt set by the first call");
    }

    // -------------------------------------------------------------------------
    // evaluateBarrierStuck — ChannelStore + MessageStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateBarrierStuck_firesAlert_whenContributorMissing() {
        Channel barrierCh = channelStore.put(Channel.builder("barrier-stuck-" + UUID.randomUUID())
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(java.util.List.of("agent-x", "agent-y"))
                .lastActivityAt(Instant.now().minusSeconds(400)).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-barrier-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("BARRIER_STUCK", barrierCh.name()).thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        // Only agent-x has contributed (non-EVENT counts; agent-y is missing)
        messageStore.put(Message.builder().channelId(barrierCh.id()).sender("agent-x").messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("contribution").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(),
                "BARRIER_STUCK should fire when contributor agent-y has not written");
        assertTrue(alerts.get(0).content().contains("BARRIER_STUCK"),
                "Alert content should identify BARRIER_STUCK condition");
    }

    @Test
    @TestTransaction
    void evaluateBarrierStuck_noAlert_whenAllContributorsPresent() {
        Channel barrierCh = channelStore.put(Channel.builder("barrier-complete-" + UUID.randomUUID())
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(java.util.List.of("agent-x", "agent-y"))
                .lastActivityAt(Instant.now().minusSeconds(400)).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-barrier-complete-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("BARRIER_STUCK", barrierCh.name()).thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        // Both contributors have written non-EVENT messages
        for (String sender : List.of("agent-x", "agent-y")) {
            messageStore.put(Message.builder().channelId(barrierCh.id()).sender(sender).messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("contribution").build());
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "BARRIER_STUCK must not fire when all contributors have written non-EVENT messages");
    }

    @Test
    @TestTransaction
    void evaluateBarrierStuck_noAlert_whenChannelHasRecentActivity() {
        Channel barrierCh = channelStore.put(Channel.builder("barrier-recent-" + UUID.randomUUID())
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(java.util.List.of("agent-x", "agent-y"))
                .lastActivityAt(Instant.now()).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-barrier-recent-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("BARRIER_STUCK", barrierCh.name()).thresholdSeconds(300).notificationChannel(notifCh.name()).createdBy("test").build());

        // agent-y is missing — would fire if activity were old
        messageStore.put(Message.builder().channelId(barrierCh.id()).sender("agent-x").messageType(MessageType.STATUS).actorType(ActorType.AGENT).content("contribution").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "BARRIER_STUCK must not fire when lastActivityAt is within the threshold window");
    }

    // -------------------------------------------------------------------------
    // evaluateChannelIdle — ChannelStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateChannelIdle_firesAlert_whenChannelIsIdle() {
        Channel idleCh = channelStore.put(Channel.builder("idle-old-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .lastActivityAt(Instant.now().minusSeconds(700)).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-idle-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("CHANNEL_IDLE", idleCh.name()).thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(),
                "CHANNEL_IDLE should fire when channel has not been active for > 600s");
        assertTrue(alerts.get(0).content().contains("CHANNEL_IDLE"),
                "Alert content should identify CHANNEL_IDLE condition");
    }

    @Test
    @TestTransaction
    void evaluateChannelIdle_noAlert_whenChannelIsRecent() {
        Channel activeCh = channelStore.put(Channel.builder("idle-recent-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).lastActivityAt(Instant.now().plusSeconds(1)).build());

        Channel notifCh = channelStore.put(Channel.builder("notif-idle-recent-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder("CHANNEL_IDLE", activeCh.name()).thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "CHANNEL_IDLE must not fire when channel has recent activity");
    }
}

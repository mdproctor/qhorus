package io.casehub.qhorus.runtime.watchdog;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.APPROVAL_PENDING, "*")
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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.APPROVAL_PENDING, "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.AGENT_STALE, "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.AGENT_STALE, "*").thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.QUEUE_DEPTH, queueCh.name()).thresholdCount(2).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.QUEUE_DEPTH, queueCh.name()).thresholdCount(5).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.QUEUE_DEPTH, queueCh.name()).thresholdCount(2).thresholdSeconds(300).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.BARRIER_STUCK, barrierCh.name()).thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.BARRIER_STUCK, barrierCh.name()).thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.BARRIER_STUCK, barrierCh.name()).thresholdSeconds(300).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CHANNEL_IDLE, idleCh.name()).thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

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

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CHANNEL_IDLE, activeCh.name()).thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(),
                "CHANNEL_IDLE must not fire when channel has recent activity");
    }

    @Test
    @TestTransaction
    void evaluateLoopDetected_firesAlert_whenSenderRepeatsSimilarContent() {
        String  chName  = "loop-fire-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-loop-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.LOOP_DETECTED, ch.name())
                                  .thresholdSeconds(600).thresholdCount(3).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        for (int i = 0; i < 3; i++) {
            messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                    .messageType(MessageType.STATUS).content("Processing task step " + i + " for the workflow")
                                    .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(), "LOOP_DETECTED should fire when sender repeats similar content");
        assertTrue(alerts.get(0).content().contains("LOOP_DETECTED"));
    }

    @Test
    @TestTransaction
    void evaluateLoopDetected_noAlert_whenContentIsDissimilar() {
        String  chName  = "loop-nofire-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-loop-nf-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.LOOP_DETECTED, ch.name())
                                  .thresholdSeconds(600).thresholdCount(3).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Starting database migration")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Deploying frontend assets to CDN")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Running integration test suite")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when messages are dissimilar");
    }

    @Test
    @TestTransaction
    void evaluateLoopDetected_noAlert_whenSimilarMessagesInterleavedWithDissimilar() {
        String  chName  = "loop-interleave-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-loop-il-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.LOOP_DETECTED, ch.name())
                                  .thresholdSeconds(600).thresholdCount(3).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Processing task step for the workflow")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Completely different unrelated message about deployment")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Processing task step for the workflow")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when similar messages are interleaved (consecutive algorithm)");
    }

    @Test
    @TestTransaction
    void evaluateObligationFanOut_firesAlert_whenCommandHasNoResponse() {
        String  chName  = "fanout-fire-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-fanout-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.OBLIGATION_FAN_OUT, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(UUID.randomUUID().toString())
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(), "OBLIGATION_FAN_OUT should fire when COMMAND has no response");
        assertTrue(alerts.get(0).content().contains("OBLIGATION_FAN_OUT"));
    }

    @Test
    @TestTransaction
    void evaluateObligationFanOut_noAlert_whenCommitmentIsAcknowledged() {
        String  chName  = "fanout-ack-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-fanout-ack-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.OBLIGATION_FAN_OUT, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(UUID.randomUUID().toString())
                                       .obligor("agent-b").requester("agent-a")
                                       .acknowledgedAt(Instant.now().minusSeconds(300))
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when commitment is acknowledged");
    }

    @Test
    @TestTransaction
    void evaluateObligationFanOut_noAlert_whenStatusMessageExists() {
        String  chName  = "fanout-status-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-fanout-st-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        String corrId = UUID.randomUUID().toString();
        watchdogStore.put(Watchdog.builder(WatchdogConditionType.OBLIGATION_FAN_OUT, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(corrId)
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("Working on it")
                                .correlationId(corrId).actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when STATUS message exists on correlationId");
    }

    @Test
    @TestTransaction
    void evaluateConversationStall_firesAlert_whenNoTerminalResolution() {
        String  chName  = "stall-fire-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-stall-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CONVERSATION_STALL, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(UUID.randomUUID().toString())
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("Still working")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(), "CONVERSATION_STALL should fire when no terminal resolution");
        assertTrue(alerts.get(0).content().contains("CONVERSATION_STALL"));
    }

    @Test
    @TestTransaction
    void evaluateConversationStall_noAlert_whenRecentTerminalExists() {
        String  chName  = "stall-term-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-stall-t-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        String corrId = UUID.randomUUID().toString();
        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CONVERSATION_STALL, ch.name())
                                  .thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(corrId)
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(1200))
                                       .build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.DONE).content("Completed")
                                .correlationId(corrId).actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when correlation has recent terminal resolution");
    }

    @Test
    @TestTransaction
    void evaluateConversationStall_noAlert_whenNoActiveCommitments() {
        String  chName  = "stall-empty-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-stall-e-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CONVERSATION_STALL, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when channel has no active commitments");
    }

    @Test
    @TestTransaction
    void evaluateConversationStall_noAlert_whenCommitmentsAreYoung() {
        String  chName  = "stall-young-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-stall-y-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CONVERSATION_STALL, ch.name())
                                  .thresholdSeconds(600).notificationChannel(notifCh.name()).createdBy("test").build());

        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(UUID.randomUUID().toString())
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(10))
                                       .build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when all commitments are younger than threshold");
    }

    @Test
    @TestTransaction
    void evaluateConversationStall_firesAlert_whenOneStalledAndOneResolved() {
        String  chName  = "stall-partial-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-stall-p-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.CONVERSATION_STALL, ch.name())
                                  .thresholdSeconds(0).notificationChannel(notifCh.name()).createdBy("test").build());

        String corrA = UUID.randomUUID().toString();
        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(corrA)
                                       .obligor("agent-b").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());

        String corrB = UUID.randomUUID().toString();
        commitmentStore.save(Commitment.builder()
                                       .state(CommitmentState.OPEN).messageType(MessageType.COMMAND)
                                       .channelId(ch.id()).correlationId(corrB)
                                       .obligor("agent-c").requester("agent-a")
                                       .createdAt(Instant.now().minusSeconds(600))
                                       .build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-c")
                                .messageType(MessageType.DONE).content("Done")
                                .correlationId(corrB).actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(), "Should fire when one correlation is stalled even if another resolved");
    }

    @Test
    @TestTransaction
    void evaluateEchoChamber_firesAlert_whenMultipleSendersRelayIdenticalContent() {
        String  chName  = "echo-fire-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-echo-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.ECHO_CHAMBER, ch.name())
                                  .thresholdSeconds(600).thresholdCount(2).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("The deployment pipeline is running for the production environment")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("The deployment pipeline is running for the production environment now")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Confirmed the deployment pipeline is running for the production environment")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("Yes the deployment pipeline is running for the production environment")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertFalse(alerts.isEmpty(), "ECHO_CHAMBER should fire when agents echo similar content");
        assertTrue(alerts.get(0).content().contains("ECHO_CHAMBER"));
    }

    @Test
    @TestTransaction
    void evaluateEchoChamber_noAlert_whenContentIsTransformed() {
        String  chName  = "echo-transform-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-echo-t-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.ECHO_CHAMBER, ch.name())
                                  .thresholdSeconds(600).thresholdCount(2).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Analyze the quarterly revenue data")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("Revenue grew 15% year over year driven by subscription renewals")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire when content is meaningfully transformed");
    }

    @Test
    @TestTransaction
    void evaluateEchoChamber_noAlert_whenOnlySingleSender() {
        String  chName  = "echo-single-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-echo-s-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.ECHO_CHAMBER, ch.name())
                                  .thresholdSeconds(600).thresholdCount(2).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Same content repeated")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Same content repeated again")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire with only one sender (below min_agents)");
    }

    @Test
    @TestTransaction
    void evaluateEchoChamber_noAlert_whenOnlyOneSimilarPair() {
        String  chName  = "echo-onepair-" + UUID.randomUUID();
        Channel ch      = channelStore.put(Channel.builder(chName).semantic(ChannelSemantic.APPEND).build());
        Channel notifCh = channelStore.put(Channel.builder("notif-echo-op-" + UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build());

        watchdogStore.put(Watchdog.builder(WatchdogConditionType.ECHO_CHAMBER, ch.name())
                                  .thresholdSeconds(600).thresholdCount(2).similarityPct(70)
                                  .notificationChannel(notifCh.name()).createdBy("test").build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                                .messageType(MessageType.STATUS).content("Deploy the application to production cluster immediately")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());
        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-b")
                                .messageType(MessageType.STATUS).content("Deploy the application to production cluster immediately")
                                .actorType(ActorType.AGENT).createdAt(Instant.now()).build());

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id()));
        assertTrue(alerts.isEmpty(), "Should not fire with only one similar cross-sender pair");
    }
}

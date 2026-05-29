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
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.casehub.qhorus.runtime.store.InstanceStore;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.WatchdogStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
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
        Channel notifCh = new Channel();
        notifCh.name = "notif-approval-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "APPROVAL_PENDING";
        w.targetName = "*";
        w.thresholdSeconds = 0;  // fires for all commitments with any expiry
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        Commitment c = new Commitment();
        c.state = CommitmentState.OPEN;
        c.expiresAt = Instant.now().plusSeconds(30);
        c.channelId = UUID.randomUUID();
        c.correlationId = UUID.randomUUID().toString();
        c.messageType = MessageType.QUERY;  // type is not filtered by evaluateApprovalPending
        c.obligor = "agent-a";
        c.requester = "agent-b";
        commitmentStore.save(c);

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertFalse(alerts.isEmpty(),
                "APPROVAL_PENDING should trigger when open commitment has expiresAt set and threshold=0");
        assertTrue(alerts.get(0).content.contains("APPROVAL_PENDING"),
                "Alert content should identify the APPROVAL_PENDING condition type");
    }

    @Test
    @TestTransaction
    void evaluateApprovalPending_noAlert_whenOpenCommitmentHasNoExpiry() {
        Channel notifCh = new Channel();
        notifCh.name = "notif-approval-no-expiry-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "APPROVAL_PENDING";
        w.targetName = "*";
        w.thresholdSeconds = 0;
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        Commitment c = new Commitment();
        c.state = CommitmentState.OPEN;
        c.expiresAt = null;  // no expiry — filter(c -> c.expiresAt != null) must exclude this
        c.channelId = UUID.randomUUID();
        c.correlationId = UUID.randomUUID().toString();
        c.messageType = MessageType.QUERY;  // type is not filtered by evaluateApprovalPending
        c.obligor = "agent-a";
        c.requester = "agent-b";
        commitmentStore.save(c);

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertTrue(alerts.isEmpty(),
                "APPROVAL_PENDING must not fire when commitment has no expiresAt — the expiresAt filter must exclude it");
    }

    // -------------------------------------------------------------------------
    // evaluateAgentStale — InstanceStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateAgentStale_firesAlert_whenStaleInstanceExists() {
        Channel notifCh = new Channel();
        notifCh.name = "notif-stale-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "AGENT_STALE";
        w.targetName = "*";
        w.thresholdSeconds = 0;  // cutoff = now; any past lastSeen matches
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        Instance inst = new Instance();
        inst.instanceId = "stale-agent-" + UUID.randomUUID();
        inst.status = "stale";
        inst.lastSeen = Instant.now().minusSeconds(10);
        instanceStore.put(inst);

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertFalse(alerts.isEmpty(),
                "AGENT_STALE should trigger when an instance with status='stale' exists and threshold=0");
        assertTrue(alerts.get(0).content.contains("AGENT_STALE"),
                "Alert content should identify the AGENT_STALE condition type");
    }

    @Test
    @TestTransaction
    void evaluateAgentStale_noAlert_whenInstanceIsNotStale() {
        Channel notifCh = new Channel();
        notifCh.name = "notif-stale-online-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "AGENT_STALE";
        w.targetName = "*";
        w.thresholdSeconds = 0;  // cutoff = now; lastSeen in the past would match
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        // Instance exists but is online — InstanceQuery.status("stale") filter must exclude it
        Instance inst = new Instance();
        inst.instanceId = "online-agent-" + UUID.randomUUID();
        inst.status = "online";
        inst.lastSeen = Instant.now().minusSeconds(10);
        instanceStore.put(inst);

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
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
        Channel queueCh = new Channel();
        queueCh.name = "queue-ch-" + UUID.randomUUID();
        queueCh.semantic = ChannelSemantic.COLLECT;
        queueCh = channelStore.put(queueCh);

        Channel notifCh = new Channel();
        notifCh.name = "notif-queue-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "QUEUE_DEPTH";
        w.targetName = queueCh.name;
        w.thresholdCount = 2;
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        // 3 non-EVENT messages — exceeds threshold of 2
        for (int i = 0; i < 3; i++) {
            Message m = new Message();
            m.channelId = queueCh.id;
            m.sender = "agent-" + i;
            m.messageType = MessageType.STATUS;
            m.actorType = ActorType.AGENT;
            m.content = "work item " + i;
            messageStore.put(m);
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertFalse(alerts.isEmpty(),
                "QUEUE_DEPTH should trigger when non-EVENT message count (3) exceeds threshold (2)");
        assertTrue(alerts.get(0).content.contains("QUEUE_DEPTH"),
                "Alert content should identify the QUEUE_DEPTH condition type");
    }

    @Test
    @TestTransaction
    void evaluateQueueDepth_noAlert_whenBelowThreshold() {
        Channel queueCh = new Channel();
        queueCh.name = "queue-below-" + UUID.randomUUID();
        queueCh.semantic = ChannelSemantic.COLLECT;
        queueCh = channelStore.put(queueCh);

        Channel notifCh = new Channel();
        notifCh.name = "notif-queue-below-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "QUEUE_DEPTH";
        w.targetName = queueCh.name;
        w.thresholdCount = 5;
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        // 2 messages — below threshold of 5
        for (int i = 0; i < 2; i++) {
            Message m = new Message();
            m.channelId = queueCh.id;
            m.sender = "agent-" + i;
            m.messageType = MessageType.STATUS;
            m.actorType = ActorType.AGENT;
            m.content = "work item " + i;
            messageStore.put(m);
        }

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertTrue(alerts.isEmpty(),
                "QUEUE_DEPTH must not fire when message count (2) is below threshold (5)");
    }

    // -------------------------------------------------------------------------
    // Debounce — verifies lastFiredAt persistence prevents re-fire (#207)
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateAll_debounce_preventsRefireWithinWindow() {
        Channel queueCh = new Channel();
        queueCh.name = "queue-debounce-" + UUID.randomUUID();
        queueCh.semantic = ChannelSemantic.COLLECT;
        queueCh = channelStore.put(queueCh);

        Channel notifCh = new Channel();
        notifCh.name = "notif-debounce-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        notifCh = channelStore.put(notifCh);

        Watchdog w = new Watchdog();
        w.conditionType = "QUEUE_DEPTH";
        w.targetName = queueCh.name;
        w.thresholdCount = 2;
        w.thresholdSeconds = 300;  // 5-minute debounce window
        w.notificationChannel = notifCh.name;
        w.createdBy = "test";
        watchdogStore.put(w);

        // 3 messages — condition is met on both calls
        for (int i = 0; i < 3; i++) {
            Message m = new Message();
            m.channelId = queueCh.id;
            m.sender = "agent-" + i;
            m.messageType = MessageType.STATUS;
            m.actorType = ActorType.AGENT;
            m.content = "work item " + i;
            messageStore.put(m);
        }

        // First evaluation — condition met, alert fires, lastFiredAt is stamped
        watchdogService.evaluateAll();
        List<Message> alertsAfterFirst = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertEquals(1, alertsAfterFirst.size(),
                "First evaluateAll() should produce exactly one alert when condition is met");

        // Second evaluation — condition still met, but debounce window (300s) has not expired
        watchdogService.evaluateAll();
        List<Message> alertsAfterSecond = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertEquals(1, alertsAfterSecond.size(),
                "Second evaluateAll() within the debounce window must not produce a second alert — isDebounced() must see the lastFiredAt set by the first call");
    }
}

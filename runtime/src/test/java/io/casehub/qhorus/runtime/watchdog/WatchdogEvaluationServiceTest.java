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
 * <p>Refs #205
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
    // Fix 1: evaluateApprovalPending — CommitmentStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateApprovalPending_firesAlert_whenOpenCommitmentWithExpiryExists() {
        Channel notifCh = new Channel();
        notifCh.name = "notif-approval-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        channelStore.put(notifCh);

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
        c.messageType = MessageType.QUERY;
        c.obligor = "agent-a";
        c.requester = "agent-b";
        commitmentStore.save(c);

        watchdogService.evaluateAll();

        List<Message> alerts = messageStore.scan(MessageQuery.forChannel(notifCh.id));
        assertFalse(alerts.isEmpty(), "APPROVAL_PENDING watchdog should fire alert");
        assertTrue(alerts.get(0).content.contains("APPROVAL_PENDING"));
    }

    // -------------------------------------------------------------------------
    // Fix 2: evaluateAgentStale — InstanceStore seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateAgentStale_firesAlert_whenStaleInstanceExists() {
        Channel notifCh = new Channel();
        notifCh.name = "notif-stale-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        channelStore.put(notifCh);

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
        assertFalse(alerts.isEmpty(), "AGENT_STALE watchdog should fire alert");
        assertTrue(alerts.get(0).content.contains("AGENT_STALE"));
    }

    // -------------------------------------------------------------------------
    // Fix 3: evaluateQueueDepth — MessageStore.count() seam
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void evaluateQueueDepth_firesAlert_whenNonEventMessageCountExceedsThreshold() {
        // evaluateQueueDepth calls channelService.listAll() — the queue channel
        // must be in the store so channelService.listAll() returns it.
        Channel queueCh = new Channel();
        queueCh.name = "queue-ch-" + UUID.randomUUID();
        queueCh.semantic = ChannelSemantic.COLLECT;
        channelStore.put(queueCh);

        Channel notifCh = new Channel();
        notifCh.name = "notif-queue-" + UUID.randomUUID();
        notifCh.semantic = ChannelSemantic.APPEND;
        channelStore.put(notifCh);

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
        assertFalse(alerts.isEmpty(), "QUEUE_DEPTH watchdog should fire alert");
        assertTrue(alerts.get(0).content.contains("QUEUE_DEPTH"));
    }
}

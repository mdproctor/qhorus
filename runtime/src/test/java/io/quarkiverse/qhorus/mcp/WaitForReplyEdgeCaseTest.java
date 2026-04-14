package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools.WaitResult;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Edge-case tests for wait_for_reply beyond the happy-path suite.
 *
 * Findings covered:
 * - wait_for_reply with a well-formed but unregistered instanceId UUID should not crash.
 * - Cross-channel isolation: response on wrong channel is not found.
 * - wait_for_reply on a COLLECT channel — does find the response?
 * - Zero-second timeout returns timeout immediately (no spin).
 * - The status message on timeout names the correlationId.
 * - A RESPONSE on the right channel but with a REQUEST message type sharing the same
 * correlationId is NOT matched (only RESPONSE type is matched).
 * - Multiple concurrent wait_for_reply calls with different correlationIds on the same channel.
 */
@QuarkusTest
class WaitForReplyEdgeCaseTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * instance_id in wait_for_reply is the human-readable agent name (e.g. "alice"),
     * not a raw UUID. If the name is not registered, the tool falls back to null —
     * it must NOT crash, it must time out gracefully.
     */
    @Test
    void waitForReplyWithUnknownInstanceIdTimesOutGracefully() {
        String ch = "wfr-edge-1-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        String unknownAgent = "agent-not-registered-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            // Must not throw — unknown instance_id falls back to null gracefully
            WaitResult result = assertDoesNotThrow(
                    () -> tools.waitForReply(ch, corrId, 1, unknownAgent),
                    "wait_for_reply with an unregistered instance_id should not crash");

            assertTrue(result.timedOut(),
                    "Should time out gracefully when no response arrives");
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * IMPORTANT finding (cross-domain): wait_for_reply searches for RESPONSE messages in a
     * SPECIFIC channel. If the responder posts to a different channel, wait_for_reply will
     * never find it and will time out. This cross-channel isolation is correct but important
     * to document as a real failure mode in multi-agent systems.
     */
    @Test
    void waitForReplyDoesNotFindResponsePostedToWrongChannel() {
        String waitCh = "wfr-edge-2-wait-" + System.nanoTime();
        String respCh = "wfr-edge-2-resp-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(waitCh, "Wait channel", ChannelSemantic.APPEND, null);
            var resp = channelService.create(respCh, "Response channel", ChannelSemantic.APPEND, null);
            // Response posted to the WRONG channel
            messageService.send(resp.id, "bob", MessageType.RESPONSE, "Answer on wrong channel", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(waitCh, corrId, 1, null);
            assertTrue(result.timedOut(),
                    "wait_for_reply must not find a response posted to a different channel");
            assertFalse(result.found());
        } finally {
            cleanupChannel(waitCh, corrId);
            cleanupChannel(respCh, null);
        }
    }

    /**
     * IMPORTANT: wait_for_reply on a COLLECT channel — the response exists in the channel.
     * findResponseByCorrelationId queries the channel by ID regardless of semantic.
     * This should find the response (COLLECT semantics are irrelevant to wait_for_reply).
     */
    @Test
    void waitForReplyWorksOnCollectChannel() {
        String ch = "wfr-edge-3-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "COLLECT channel", ChannelSemantic.COLLECT, null);
            messageService.send(channel.id, "alice", MessageType.REQUEST, "Q", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "A on COLLECT", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);
            assertTrue(result.found(), "wait_for_reply should find a RESPONSE in a COLLECT channel");
            assertEquals("A on COLLECT", result.message().content());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * CREATIVE: wait_for_reply on a BARRIER channel — same as above; the semantic is irrelevant
     * to the underlying RESPONSE query. The response exists, so it is found immediately.
     */
    @Test
    void waitForReplyWorksOnBarrierChannel() {
        String ch = "wfr-edge-4-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "BARRIER channel", ChannelSemantic.BARRIER, "alice,bob");
            messageService.send(channel.id, "alice", MessageType.REQUEST, "Q", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "A on BARRIER", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);
            assertTrue(result.found(),
                    "wait_for_reply should find a RESPONSE in a BARRIER channel regardless of barrier state");
            assertEquals("A on BARRIER", result.message().content());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * CREATIVE: zero-second timeout — the loop condition `Instant.now().isBefore(expiresAt)`
     * where expiresAt = Instant.now().plusSeconds(0) may or may not execute one poll.
     * This tests that a timeout of 0 eventually returns timedOut=true without hanging.
     */
    @Test
    void waitForReplyWithZeroSecondTimeoutReturnsTimedOut() {
        String ch = "wfr-edge-5-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            long start = System.currentTimeMillis();
            WaitResult result = tools.waitForReply(ch, corrId, 0, null);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result.timedOut(), "timeout=0 should result in timedOut=true");
            assertFalse(result.found());
            // Should complete quickly — not spin for a long time
            assertTrue(elapsed < 5000,
                    "timeout=0 should return promptly; took " + elapsed + "ms");
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * IMPORTANT: the timeout status message must name the correlationId so agents can
     * diagnose which request timed out.
     */
    @Test
    void waitForReplyTimeoutStatusMessageContainsCorrelationId() {
        String ch = "wfr-edge-6-" + System.nanoTime();
        String corrId = "corr-unique-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 1, null);

            assertTrue(result.timedOut());
            assertNotNull(result.status());
            assertTrue(result.status().contains(corrId),
                    "timeout status message must contain the correlationId so agents can diagnose the failure; " +
                            "got: " + result.status());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * CREATIVE: multiple wait_for_reply calls on the same channel with DIFFERENT
     * correlationIds — each should resolve independently without interfering with the other.
     * Both responses are pre-committed so each waiter finds its response immediately.
     *
     * Note: CDI beans cannot be used directly from background threads without an active
     * request context. This test validates the isolation property sequentially (each waiter
     * resolves immediately from the pre-committed response), which is sufficient to confirm
     * that correlationId matching is per-corrId not per-channel.
     */
    @Test
    void waitForReplyMultipleWaitersResolveIndependentlyOnSameChannel() {
        String ch = "wfr-edge-7-" + System.nanoTime();
        String corrIdA = "corr-A-" + UUID.randomUUID();
        String corrIdB = "corr-B-" + UUID.randomUUID();

        // Pre-commit both responses
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE, "Answer-A", corrIdA, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE, "Answer-B", corrIdB, null);
        });

        try {
            // Waiter A — finds Answer-A immediately
            WaitResult resultA = tools.waitForReply(ch, corrIdA, 5, null);
            assertTrue(resultA.found(), "waiter A should have found its response");
            assertEquals("Answer-A", resultA.message().content(),
                    "waiter A must receive Answer-A, not Answer-B");

            // Waiter B — finds Answer-B immediately (Answer-A has a different corrId)
            WaitResult resultB = tools.waitForReply(ch, corrIdB, 5, null);
            assertTrue(resultB.found(), "waiter B should have found its response");
            assertEquals("Answer-B", resultB.message().content(),
                    "waiter B must receive Answer-B, not Answer-A");
        } finally {
            cleanupChannel(ch, corrIdA);
            QuarkusTransaction.requiringNew().run(
                    () -> PendingReply.delete("correlationId", corrIdB));
            cleanupChannel(ch, null);
        }
    }

    /**
     * CREATIVE: wait_for_reply where a REQUEST message (not RESPONSE) carries the matching
     * correlationId. The waiter must NOT pick up the REQUEST — only RESPONSE type matches.
     * (Already tested in the main suite, but this tests it on a channel that also has a
     * matching-corrId REQUEST, to confirm the type filter is applied before content inspection.)
     */
    @Test
    void waitForReplyIgnoresRequestWithMatchingCorrelationId() {
        String ch = "wfr-edge-8-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            // Only a REQUEST with the correlationId — no RESPONSE
            messageService.send(channel.id, "alice", MessageType.REQUEST, "Question", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 1, null);
            assertFalse(result.found(),
                    "wait_for_reply must not match a REQUEST message even when correlationId matches");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    /**
     * IMPORTANT: PendingReply is cleaned up on timeout even when the channel is eventually
     * deleted. The cleanup job uses expiresAt, not the channel's existence. Confirm that
     * a PendingReply registered for a now-deleted channel can be cleaned up without DB errors.
     */
    @Test
    void pendingReplyCleanupHandlesOrphanedChannelReference() {
        String ch = "wfr-edge-9-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        // wait_for_reply will register a PendingReply then time out and delete it
        WaitResult result = tools.waitForReply(ch, corrId, 1, null);
        assertTrue(result.timedOut());

        // Delete the channel (simulate cleanup or GC)
        QuarkusTransaction.requiringNew().run(
                () -> io.quarkiverse.qhorus.runtime.channel.Channel.delete("name", ch));

        // PendingReply was already cleaned up by timeout path — nothing should remain
        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> PendingReply.count("correlationId", corrId));
        assertEquals(0, remaining,
                "PendingReply should already be deleted after timeout; no rows should remain");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void cleanupChannel(String channelName, String corrId) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (corrId != null) {
                PendingReply.delete("correlationId", corrId);
            }
            channelService.findByName(channelName).ifPresent(ch -> {
                Message.delete("channelId", ch.id);
            });
            io.quarkiverse.qhorus.runtime.channel.Channel.delete("name", channelName);
        });
    }
}

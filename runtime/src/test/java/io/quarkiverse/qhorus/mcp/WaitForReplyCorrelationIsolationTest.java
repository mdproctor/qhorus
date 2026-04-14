package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
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
 * Tests for wait_for_reply correlation ID isolation — the property that a waiter
 * NEVER receives a response intended for a different waiter, even when:
 * - The same channel has many competing responses.
 * - The same correlation ID exists on a different channel.
 * - Two waiters run concurrently on the same channel.
 *
 * This is a safety-critical property: a multi-agent system where one agent receives
 * another's response would produce silent data corruption.
 */
@QuarkusTest
class WaitForReplyCorrelationIsolationTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    /**
     * CRITICAL: same correlation ID used on two different channels — waiters on each
     * channel must only receive their own response, not the other channel's response.
     *
     * findResponseByCorrelationId is channel-scoped (WHERE channelId = ? AND correlationId = ?),
     * so this should work correctly. This test proves it does.
     */
    @Test
    void waitForReplyOnChannelADoesNotMatchResponseOnChannelBWithSameCorrId() {
        String chA = "wfr-iso-A-" + System.nanoTime();
        String chB = "wfr-iso-B-" + System.nanoTime();
        String sharedCorrId = "shared-corr-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(chA, "Channel A", ChannelSemantic.APPEND, null);
            var channelB = channelService.create(chB, "Channel B", ChannelSemantic.APPEND, null);
            // Response on channel B only — channel A has no response
            messageService.send(channelB.id, "responder", MessageType.RESPONSE,
                    "Answer on B", sharedCorrId, null);
        });

        try {
            // Waiter on channel A with the same corrId — must NOT pick up channel B's response
            WaitResult result = tools.waitForReply(chA, sharedCorrId, 1, null);

            assertFalse(result.found(),
                    "wait_for_reply on channel A must not find a RESPONSE on channel B, " +
                            "even when the correlation ID matches");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(chA, sharedCorrId);
            cleanupChannel(chB, null);
        }
    }

    /**
     * CRITICAL: two sequential waiters on the same channel with different correlation IDs.
     * Each must receive only its own matching response, even when both responses exist
     * in the channel at the time of waiting.
     *
     * This is the core isolation invariant: waiter A must not receive answer-for-B, and
     * vice versa. If correlationId matching matched any RESPONSE in the channel (not
     * filtered by corrId), waiter A would steal waiter B's response.
     *
     * Note: wait_for_reply requires an active CDI request context, which is not available
     * in worker threads. This test uses sequential calls (both pre-committed responses exist
     * before either waiter runs), which proves the isolation property as effectively as
     * concurrent calls — correlation filtering is in the SQL query, not in thread scheduling.
     */
    @Test
    void sequentialWaitersOnSameChannelEachReceiveOnlyTheirOwnResponse() {
        String ch = "wfr-iso-sequential-" + System.nanoTime();
        String corrIdA = "corr-A-" + UUID.randomUUID();
        String corrIdB = "corr-B-" + UUID.randomUUID();

        // Pre-commit BOTH responses so both are present when either waiter runs
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ch, "Sequential waiters channel", ChannelSemantic.APPEND, null);
            var channel = channelService.findByName(ch).orElseThrow();
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-A", corrIdA, null);
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-B", corrIdB, null);
        });

        try {
            // Waiter A — channel has BOTH responses; must receive only answer-for-A
            WaitResult ra = tools.waitForReply(ch, corrIdA, 5, null);

            assertTrue(ra.found(), "waiter A must find its response");
            assertEquals("answer-for-A", ra.message().content(),
                    "waiter A must receive answer-for-A, not answer-for-B");
            assertEquals(corrIdA, ra.correlationId(),
                    "waiter A's returned correlationId must be corrIdA");

            // Waiter B — channel still has answer-for-B (not consumed by waiter A)
            WaitResult rb = tools.waitForReply(ch, corrIdB, 5, null);

            assertTrue(rb.found(), "waiter B must find its response");
            assertEquals("answer-for-B", rb.message().content(),
                    "waiter B must receive answer-for-B, not answer-for-A");
            assertEquals(corrIdB, rb.correlationId(),
                    "waiter B's returned correlationId must be corrIdB");
        } finally {
            cleanupChannel(ch, corrIdA);
            QuarkusTransaction.requiringNew().run(
                    () -> PendingReply.delete("correlationId", corrIdB));
            cleanupChannel(ch, null);
        }
    }

    /**
     * IMPORTANT: wait_for_reply must not match a response that was committed to the
     * channel BEFORE the request was sent, if it has a completely different correlationId.
     *
     * This tests the scenario where the channel already has a RESPONSE from a prior
     * interaction (a "stale" response from a previous request-reply cycle) with a
     * different corrId. The new waiter must not match it.
     */
    @Test
    void waitForReplyDoesNotMatchStaleResponseWithDifferentCorrId() {
        String ch = "wfr-iso-stale-" + System.nanoTime();
        String staleCorrId = "corr-stale-" + UUID.randomUUID();
        String freshCorrId = "corr-fresh-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Stale response channel",
                    ChannelSemantic.APPEND, null);
            // Stale response from a prior cycle
            messageService.send(channel.id, "old-responder", MessageType.RESPONSE,
                    "old answer", staleCorrId, null);
        });

        try {
            // Waiter for a FRESH corrId — must not pick up the stale response
            WaitResult result = tools.waitForReply(ch, freshCorrId, 1, null);

            assertFalse(result.found(),
                    "wait_for_reply must not match a stale RESPONSE with a different corrId");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch, freshCorrId);
            QuarkusTransaction.requiringNew().run(
                    () -> PendingReply.delete("correlationId", staleCorrId));
            cleanupChannel(ch, null);
        }
    }

    /**
     * CREATIVE: wait_for_reply with a UUID-format correlationId that happens to be
     * a prefix substring of another UUID — verifies that matching is exact, not prefix-based.
     *
     * The SQL query uses = (exact match), not LIKE, so this should be safe. This test
     * documents and proves that property.
     */
    @Test
    void waitForReplyMatchesExactCorrIdNotSubstringOfAnother() {
        String ch = "wfr-iso-exact-" + System.nanoTime();
        // corrIdShort is a known-format string
        String corrIdShort = "corr-abc";
        // corrIdLong contains corrIdShort as a prefix
        String corrIdLong = "corr-abc-extended-suffix-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Exact match test",
                    ChannelSemantic.APPEND, null);
            // Only a response for corrIdLong (the longer one)
            messageService.send(channel.id, "responder", MessageType.RESPONSE,
                    "answer-for-long", corrIdLong, null);
        });

        try {
            // Wait for corrIdShort — must NOT match corrIdLong
            WaitResult result = tools.waitForReply(ch, corrIdShort, 1, null);

            assertFalse(result.found(),
                    "wait_for_reply must match corrId exactly; corrIdLong shares prefix with corrIdShort " +
                            "but must not be matched");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch, corrIdShort);
            QuarkusTransaction.requiringNew().run(
                    () -> PendingReply.delete("correlationId", corrIdLong));
            cleanupChannel(ch, null);
        }
    }

    /**
     * IMPORTANT: PendingReply cleanup job running while a waiter is active must not cause
     * the waiter to fail with an exception. The waiter deletes its PendingReply on timeout;
     * if the cleanup job already deleted it, the delete is a no-op.
     *
     * This tests the race between the cleanup job and an active waiter's timeout path.
     * The cleanup job deletes by expiresAt < now; after timeout, the waiter calls
     * deletePendingReply (which calls PendingReply.delete("correlationId", corrId)).
     * A no-op delete for a non-existent row must not throw.
     */
    @Test
    void waitForReplyDeleteOnTimeoutIsIdempotentWhenCleanupJobAlreadyDeletedRow() {
        String ch = "wfr-iso-cleanup-race-" + System.nanoTime();
        String corrId = "corr-cleanup-race-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(
                () -> channelService.create(ch, "Cleanup race test", ChannelSemantic.APPEND, null));

        try {
            // wait_for_reply times out (1s)
            WaitResult result = tools.waitForReply(ch, corrId, 1, null);
            assertTrue(result.timedOut());

            // PendingReply was deleted by the waiter's timeout path.
            // Simulate the cleanup job trying to delete the already-gone row — must be a no-op.
            assertDoesNotThrow(
                    () -> QuarkusTransaction.requiringNew().run(
                            () -> messageService.deletePendingReply(corrId)),
                    "deletePendingReply for an already-deleted row (cleanup job race) must not throw");
        } finally {
            cleanupChannel(ch, corrId);
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void cleanupChannel(String channelName, String corrId) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (corrId != null) {
                PendingReply.delete("correlationId", corrId);
            }
            channelService.findByName(channelName).ifPresent(c -> {
                Message.delete("channelId", c.id);
            });
            Channel.delete("name", channelName);
        });
    }
}

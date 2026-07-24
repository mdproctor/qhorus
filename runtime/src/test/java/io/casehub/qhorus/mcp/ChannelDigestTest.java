package io.casehub.qhorus.mcp;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #43 — Channel digest: get_channel_digest MCP tool for human dashboards.
 *
 * <p>
 * get_channel_digest returns a structured overview of a channel's activity:
 * message count, sender/type breakdowns, artefact ref count, recent messages
 * (truncated), and oldest/newest timestamps.
 *
 * <p>
 * Refs #43, Epic #36.
 */
@QuarkusTest
class ChannelDigestTest {

    @Inject
    QhorusMcpTools tools;

    // -------------------------------------------------------------------------
    // Unit — field correctness
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void digestEmptyChannelReturnsZerosAndNulls() {
        tools.createChannel("cd-empty-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-empty-1", null);

        assertNotNull(digest);
        assertEquals("cd-empty-1", digest.channelName());
        assertEquals(0L, digest.messageCount());
        assertTrue(digest.senderBreakdown().isEmpty());
        assertTrue(digest.typeBreakdown().isEmpty());
        assertEquals(0, digest.artefactRefCount());
        assertNull(digest.oldestMessageAt());
        assertNull(digest.newestMessageAt());
        assertTrue(digest.recentMessages().isEmpty());
    }

    @Test
    @TestTransaction
    void digestCorrectlyCountsMessages() {
        tools.createChannel("cd-count-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-count-1", "alice", "command", "msg1", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-count-1", "bob", "status", "msg2", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-count-1", "carol", "status", "msg3", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-count-1", null);

        assertEquals(3L, digest.messageCount());
    }

    @Test
    @TestTransaction
    void digestSenderBreakdownIsCorrect() {
        tools.createChannel("cd-sender-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-sender-1", "alice", "status", "a", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-sender-1", "alice", "status", "b", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-sender-1", "bob", "status", "c", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-sender-1", null);

        assertEquals(2, digest.senderBreakdown().get("alice"));
        assertEquals(1, digest.senderBreakdown().get("bob"));
    }

    @Test
    @TestTransaction
    void digestTypeBreakdownIsCorrect() {
        tools.createChannel("cd-type-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-type-1", "alice", "query", "q", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-type-1", "bob", "status", "a", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-type-1", "bob", "status", "b", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-type-1", null);

        assertEquals(1, digest.typeBreakdown().get("QUERY"));
        assertEquals(2, digest.typeBreakdown().get("STATUS"));
    }

    @Test
    @TestTransaction
    void digestArtefactRefCountIsCorrect() {
        tools.createChannel("cd-refs-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        QhorusMcpTools.ArtefactDetail a1 = tools.shareArtefact("cd-art-1", "d", "alice", "c", false, true);
        QhorusMcpTools.ArtefactDetail a2 = tools.shareArtefact("cd-art-2", "d", "alice", "c", false, true);

        // Message 1 references both artefacts
        tools.sendMessage("cd-refs-1", "alice", "status", "msg", null, null, List.of(a1.artefactId().toString(), a2.artefactId().toString()), null, null, null, null, null);
        // Message 2 references artefact 1 again (same UUID, not double-counted)
        tools.sendMessage("cd-refs-1", "bob", "status", "msg2", null, null, List.of(a1.artefactId().toString()), null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-refs-1", null);

        assertEquals(2, digest.artefactRefCount(),
                "distinct artefact UUIDs across all messages: 2");
    }

    @Test
    @TestTransaction
    void digestRecentMessagesRespectLimit() {
        tools.createChannel("cd-limit-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        for (int i = 0; i < 8; i++) {
            tools.sendMessage("cd-limit-1", "alice", "status", "msg" + i, null, null, null, null, null, null, null, null);
        }

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-limit-1", 3);

        assertEquals(3, digest.recentMessages().size(), "recentMessages should be limited to 3");
        assertEquals(8L, digest.messageCount(), "total count should still be 8");
    }

    @Test
    @TestTransaction
    void digestContentTruncatedAt120Chars() {
        tools.createChannel("cd-trunc-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        String longContent = "x".repeat(200);
        tools.sendMessage("cd-trunc-1", "alice", "status", longContent, null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-trunc-1", null);

        String preview = digest.recentMessages().get(0).contentPreview();
        assertTrue(preview.length() <= 121, "preview should not exceed 120 chars + ellipsis");
        assertTrue(preview.endsWith("…"), "preview should end with ellipsis when truncated");
    }

    @Test
    @TestTransaction
    void digestContentNotTruncatedWhenShort() {
        tools.createChannel("cd-trunc-2", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-trunc-2", "alice", "status", "short", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-trunc-2", null);

        assertEquals("short", digest.recentMessages().get(0).contentPreview());
    }

    @Test
    @TestTransaction
    void digestOldestAndNewestTimestampsPresent() {
        tools.createChannel("cd-ts-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-ts-1", "alice", "status", "first", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-ts-1", "bob", "status", "last", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-ts-1", null);

        assertNotNull(digest.oldestMessageAt());
        assertNotNull(digest.newestMessageAt());
    }

    @Test
    @TestTransaction
    void digestReflectsPausedState() {
        tools.createChannel("cd-paused-1", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.pauseChannel("cd-paused-1", null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-paused-1", null);

        assertTrue(digest.paused(), "digest should reflect paused state");
    }

    @Test
    @TestTransaction
    void digestUnknownChannelThrows() {
        assertThrows(ToolCallException.class,
                () -> tools.channelDigest("no-such-channel", null));
    }

    // -------------------------------------------------------------------------
    // Integration — mixed senders and types
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void integrationDigestFullMixedChannel() {
        tools.createChannel("cd-int-1", "Work Channel", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-int-1", "alice", "command", "task request", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-int-1", "bob", "status", "bob's response", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-int-1", "alice", "status", "alice status", null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-int-1", "carol", "status", "carol status", null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-int-1", 10);

        assertEquals(4L, digest.messageCount());
        assertEquals(2, digest.senderBreakdown().get("alice"));
        assertEquals(1, digest.senderBreakdown().get("bob"));
        assertEquals(1, digest.senderBreakdown().get("carol"));
        assertEquals(1, digest.typeBreakdown().get("COMMAND"));
        assertEquals(3, digest.typeBreakdown().get("STATUS"));
        assertEquals(4, digest.recentMessages().size());
        assertNotNull(digest.oldestMessageAt());
        assertNotNull(digest.newestMessageAt());
    }

    // -------------------------------------------------------------------------
    // E2E — human reviews channel state before intervening
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void e2eHumanReviewsDigestBeforeForceRelease() {
        tools.createChannel("cd-e2e-1", "Review Channel", "BARRIER", "alice,bob", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        tools.sendMessage("cd-e2e-1", "alice", "status", "Alice's detailed review: all good", null, null, null, null, null, null, null, null);

        // Human calls get_channel_digest to understand state before intervening
        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-e2e-1", 5);

        assertEquals(1L, digest.messageCount());
        assertEquals(1, digest.senderBreakdown().get("alice"));
        assertEquals("BARRIER", digest.semantic());
        assertFalse(digest.paused());
        assertEquals("Alice's detailed review: all good",
                digest.recentMessages().get(0).contentPreview());

        // Human decides to force-release based on digest
        QhorusMcpTools.ForceReleaseResult release = tools.forceReleaseChannel("cd-e2e-1", "bob unavailable", null);
        assertEquals(1, release.messageCount());
    }

    @Test
    @TestTransaction
    void digestEmptyChannelHasEmptyTopicBreakdown() {
        tools.createChannel("cd-topic-empty", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-topic-empty", null);

        assertNotNull(digest.topicBreakdown());
        assertTrue(digest.topicBreakdown().isEmpty());
    }

    @Test
    @TestTransaction
    void digestShowsTopicBreakdownWithCounts() {
        tools.createChannel("cd-topic-count", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-topic-count", "alice", "status", "msg1", null, null, null, null, null, null, null, "design");
        tools.sendMessage("cd-topic-count", "bob", "status", "msg2", null, null, null, null, null, null, null, "design");
        tools.sendMessage("cd-topic-count", "carol", "status", "msg3", null, null, null, null, null, null, null, "testing");

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-topic-count", null);

        assertNotNull(digest.topicBreakdown());
        assertFalse(digest.topicBreakdown().isEmpty());

        var designTopic = digest.topicBreakdown().stream()
                                .filter(t -> "design".equals(t.name())).findFirst().orElseThrow();
        assertEquals(2, designTopic.messageCount());
        assertFalse(designTopic.resolved());

        var testingTopic = digest.topicBreakdown().stream()
                                 .filter(t -> "testing".equals(t.name())).findFirst().orElseThrow();
        assertEquals(1, testingTopic.messageCount());
    }

    @Test
    @TestTransaction
    void digestShowsResolvedTopicStatus() {
        tools.createChannel("cd-topic-resolved", "Test", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.sendMessage("cd-topic-resolved", "alice", "status", "msg1", null, null, null, null, null, null, null, "review");
        tools.resolveTopic("cd-topic-resolved", "review", "alice");

        QhorusMcpTools.ChannelDigest digest = tools.channelDigest("cd-topic-resolved", null);

        var reviewTopic = digest.topicBreakdown().stream()
                                .filter(t -> "review".equals(t.name())).findFirst().orElseThrow();
        assertTrue(reviewTopic.resolved());
        assertNotNull(reviewTopic.resolvedAt());
    }


}

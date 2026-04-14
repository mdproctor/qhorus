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
 * Tests for wait_for_reply. No @TestTransaction — the tool manages its own
 * short per-poll transactions internally and would not see uncommitted
 *
 * @TestTransaction data. Each test manages setup/teardown with QuarkusTransaction.
 */
@QuarkusTest
class WaitForReplyTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    // -----------------------------------------------------------------------
    // Happy path — response already exists when wait registers
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyReturnsImmediatelyWhenResponseAlreadyExists() {
        String ch = "wfr-exists-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.REQUEST, "Question", corrId, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Answer!", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 5, null);

            assertTrue(result.found());
            assertFalse(result.timedOut());
            assertEquals(corrId, result.correlationId());
            assertNotNull(result.message());
            assertEquals("Answer!", result.message().content());
            assertEquals("RESPONSE", result.message().messageType());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Timeout path
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyTimesOutWhenNoResponseArrives() {
        String ch = "wfr-timeout-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 1, null); // 1s timeout

            assertFalse(result.found());
            assertTrue(result.timedOut());
            assertEquals(corrId, result.correlationId());
            assertNull(result.message());
            assertNotNull(result.status());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Correlation ID matching precision
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyIgnoresResponseWithDifferentCorrelationId() {
        String ch = "wfr-diff-corr-" + System.nanoTime();
        String waitCorrId = "corr-wait-" + UUID.randomUUID();
        String otherCorrId = "corr-other-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "alice", MessageType.REQUEST, "Q", waitCorrId, null);
            // Response for a DIFFERENT correlation ID — should not wake the wait
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Wrong answer", otherCorrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, waitCorrId, 1, null);

            assertFalse(result.found(), "should not match a response with a different correlationId");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyIgnoresNonResponseMessageTypes() {
        String ch = "wfr-type-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            // STATUS and DONE messages with matching correlationId — not RESPONSE type
            messageService.send(channel.id, "alice", MessageType.STATUS, "working...", corrId, null);
            messageService.send(channel.id, "alice", MessageType.DONE, "done", corrId, null);
        });

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 1, null);

            assertFalse(result.found(), "STATUS and DONE should not satisfy wait_for_reply");
            assertTrue(result.timedOut());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // PendingReply lifecycle
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyDeletesPendingReplyOnSuccess() {
        String ch = "wfr-cleanup-ok-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var channel = channelService.create(ch, "Test", ChannelSemantic.APPEND, null);
            messageService.send(channel.id, "bob", MessageType.RESPONSE, "Answer", corrId, null);
        });

        try {
            tools.waitForReply(ch, corrId, 5, null);

            long remaining = QuarkusTransaction.requiringNew()
                    .call(() -> PendingReply.count("correlationId", corrId));
            assertEquals(0, remaining, "PendingReply should be deleted after successful match");
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyDeletesPendingReplyOnTimeout() {
        String ch = "wfr-cleanup-timeout-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            tools.waitForReply(ch, corrId, 1, null);

            long remaining = QuarkusTransaction.requiringNew()
                    .call(() -> PendingReply.count("correlationId", corrId));
            assertEquals(0, remaining, "PendingReply should be deleted after timeout");
        } finally {
            cleanupChannel(ch);
        }
    }

    @Test
    void waitForReplyUpsertsPendingReplyIfAlreadyRegistered() {
        // Calling wait_for_reply twice with the same correlationId must not
        // violate the unique constraint on PendingReply.correlationId
        String ch = "wfr-upsert-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        try {
            // First wait times out
            tools.waitForReply(ch, corrId, 1, null);
            // Second wait with same correlationId must not throw
            assertDoesNotThrow(() -> tools.waitForReply(ch, corrId, 1, null),
                    "second wait_for_reply with same correlationId should not violate unique constraint");
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Error paths
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyThrowsForUnknownChannel() {
        assertThrows(Exception.class, () -> tools.waitForReply("no-such-channel-xyz", "corr-abc", 1, null));
    }

    // -----------------------------------------------------------------------
    // Polling — response arrives AFTER the wait starts
    // -----------------------------------------------------------------------

    @Test
    void waitForReplyPollingCatchesResponseThatArrivesDuringWait() throws InterruptedException {
        String ch = "wfr-poll-" + System.nanoTime();
        String corrId = "corr-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ch, "Test", ChannelSemantic.APPEND, null));

        // Inject the response on a background thread after 300ms
        Thread responder = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            QuarkusTransaction.requiringNew().run(() -> {
                var channel = channelService.findByName(ch).orElseThrow();
                messageService.send(channel.id, "bob", MessageType.RESPONSE,
                        "late response", corrId, null);
            });
        });
        responder.start();

        try {
            WaitResult result = tools.waitForReply(ch, corrId, 3, null);
            responder.join(2000);

            assertTrue(result.found(), "should find the response that arrived during polling");
            assertEquals("late response", result.message().content());
        } finally {
            cleanupChannel(ch);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void cleanupChannel(String channelName) {
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.findByName(channelName).ifPresent(ch -> {
                PendingReply.delete("channelId", ch.id);
                Message.delete("channelId", ch.id);
            });
            io.quarkiverse.qhorus.runtime.channel.Channel.delete("name", channelName);
        });
    }
}

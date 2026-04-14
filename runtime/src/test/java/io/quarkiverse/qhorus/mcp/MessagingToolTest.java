package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessagingToolTest {

    @Inject
    QhorusMcpTools tools;

    // -----------------------------------------------------------------------
    // send_message
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void sendMessagePersistsAndReturnsResult() {
        tools.createChannel("msg-ch-1", "Test", null, null);

        MessageResult result = tools.sendMessage("msg-ch-1", "alice", "status", "Hello!", null, null);

        assertNotNull(result.messageId());
        assertEquals("msg-ch-1", result.channelName());
        assertEquals("alice", result.sender());
        assertEquals("STATUS", result.messageType());
    }

    @Test
    @TestTransaction
    void sendMessageRequestAutoGeneratesCorrelationId() {
        tools.createChannel("msg-ch-2", "Test", null, null);

        MessageResult result = tools.sendMessage("msg-ch-2", "alice", "request", "Question?", null, null);

        assertNotNull(result.correlationId(),
                "request type with no correlation_id should auto-generate one");
        assertFalse(result.correlationId().isBlank());
    }

    @Test
    @TestTransaction
    void sendMessageWithExplicitCorrelationId() {
        tools.createChannel("msg-ch-3", "Test", null, null);

        MessageResult result = tools.sendMessage("msg-ch-3", "alice", "request", "Ping", "my-corr-id", null);

        assertEquals("my-corr-id", result.correlationId());
    }

    @Test
    @TestTransaction
    void sendMessageReplyIncrementsParentReplyCount() {
        tools.createChannel("msg-ch-4", "Test", null, null);
        MessageResult request = tools.sendMessage("msg-ch-4", "alice", "request", "Question?", null, null);

        MessageResult reply = tools.sendMessage("msg-ch-4", "bob", "response", "Answer!", null, request.messageId());

        assertEquals(request.messageId(), reply.inReplyTo());
        assertEquals(1, reply.parentReplyCount());
    }

    @Test
    @TestTransaction
    void sendMessageToUnknownChannelThrows() {
        assertThrows(Exception.class, () -> tools.sendMessage("no-such-channel", "alice", "status", "Hello", null, null));
    }

    // -----------------------------------------------------------------------
    // check_messages
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void checkMessagesReturnsMessagesAfterCursor() {
        tools.createChannel("check-ch-1", "Test", null, null);
        MessageResult m1 = tools.sendMessage("check-ch-1", "alice", "status", "first", null, null);
        tools.sendMessage("check-ch-1", "bob", "status", "second", null, null);
        tools.sendMessage("check-ch-1", "carol", "status", "third", null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-1", m1.messageId(), 10, null);

        assertEquals(2, result.messages().size());
        assertEquals("second", result.messages().get(0).content());
        assertEquals("third", result.messages().get(1).content());
    }

    @Test
    @TestTransaction
    void checkMessagesExcludesEventType() {
        tools.createChannel("check-ch-2", "Test", null, null);
        MessageResult m1 = tools.sendMessage("check-ch-2", "alice", "status", "visible", null, null);
        tools.sendMessage("check-ch-2", "system", "event", "telemetry", null, null);
        tools.sendMessage("check-ch-2", "bob", "status", "also visible", null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-2", m1.messageId(), 10, null);

        assertEquals(1, result.messages().size());
        assertEquals("also visible", result.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void checkMessagesFiltersBySender() {
        tools.createChannel("check-ch-3", "Test", null, null);
        tools.sendMessage("check-ch-3", "alice", "status", "from alice", null, null);
        tools.sendMessage("check-ch-3", "bob", "status", "from bob", null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("check-ch-3", 0L, 10, "alice");

        assertEquals(1, result.messages().size());
        assertEquals("alice", result.messages().get(0).sender());
    }

    // -----------------------------------------------------------------------
    // get_replies
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void getRepliesReturnsDirectReplies() {
        tools.createChannel("replies-ch", "Test", null, null);
        MessageResult request = tools.sendMessage("replies-ch", "alice", "request", "Q?", null, null);
        tools.sendMessage("replies-ch", "bob", "response", "A1", null, request.messageId());
        tools.sendMessage("replies-ch", "carol", "response", "A2", null, request.messageId());

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(request.messageId());

        assertEquals(2, replies.size());
    }

    @Test
    @TestTransaction
    void getRepliesReturnsEmptyWhenNoReplies() {
        tools.createChannel("noreplies-ch", "Test", null, null);
        MessageResult msg = tools.sendMessage("noreplies-ch", "alice", "status", "standalone", null, null);

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(msg.messageId());

        assertTrue(replies.isEmpty());
    }

    // -----------------------------------------------------------------------
    // search_messages
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void searchMessagesFindsKeywordInContent() {
        tools.createChannel("search-ch-1", "Test", null, null);
        tools.sendMessage("search-ch-1", "alice", "status", "Found security vulnerability", null, null);
        tools.sendMessage("search-ch-1", "bob", "status", "Performance looks fine", null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("security", null, 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).content().contains("security"));
    }

    @Test
    @TestTransaction
    void searchMessagesIsCaseInsensitive() {
        tools.createChannel("search-ch-2", "Test", null, null);
        tools.sendMessage("search-ch-2", "alice", "status", "CRITICAL: auth bypass", null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("critical", null, 10);

        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void searchMessagesExcludesEventType() {
        tools.createChannel("search-ch-3", "Test", null, null);
        tools.sendMessage("search-ch-3", "system", "event", "critical system event", null, null);
        tools.sendMessage("search-ch-3", "alice", "status", "critical user message", null, null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("critical", null, 10);

        // EVENT should be excluded
        assertEquals(1, results.size());
        assertEquals("alice", results.get(0).sender());
    }
}

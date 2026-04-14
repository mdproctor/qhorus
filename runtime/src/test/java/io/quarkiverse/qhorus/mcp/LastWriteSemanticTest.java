package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools.CheckResult;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LastWriteSemanticTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void lastWriteFirstMessageSucceeds() {
        tools.createChannel("lw-1", "LAST_WRITE channel", "LAST_WRITE", null);

        MessageResult result = tools.sendMessage("lw-1", "alice", "status", "v1", null, null);

        assertNotNull(result.messageId());
    }

    @Test
    @TestTransaction
    void lastWriteSameSenderOverwritesInPlace() {
        tools.createChannel("lw-2", "LAST_WRITE channel", "LAST_WRITE", null);
        MessageResult first = tools.sendMessage("lw-2", "alice", "status", "v1", null, null);

        MessageResult second = tools.sendMessage("lw-2", "alice", "status", "v2", null, null);

        // Overwrite in place — same message ID
        assertEquals(first.messageId(), second.messageId(),
                "LAST_WRITE same-sender write should update the existing message, not insert a new one");

        // Channel has exactly one message with updated content
        CheckResult messages = tools.checkMessages("lw-2", 0L, 10, null);
        assertEquals(1, messages.messages().size());
        assertEquals("v2", messages.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void lastWriteChannelHasExactlyOneMessageAfterMultipleWrites() {
        tools.createChannel("lw-3", "LAST_WRITE channel", "LAST_WRITE", null);
        tools.sendMessage("lw-3", "alice", "status", "v1", null, null);
        tools.sendMessage("lw-3", "alice", "status", "v2", null, null);
        tools.sendMessage("lw-3", "alice", "status", "v3", null, null);

        CheckResult messages = tools.checkMessages("lw-3", 0L, 10, null);

        assertEquals(1, messages.messages().size());
        assertEquals("v3", messages.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void lastWriteDifferentSenderIsRejected() {
        tools.createChannel("lw-4", "LAST_WRITE channel", "LAST_WRITE", null);
        tools.sendMessage("lw-4", "alice", "status", "alice owns this", null, null);

        assertThrows(IllegalStateException.class, () -> tools.sendMessage("lw-4", "bob", "status", "bob tries", null, null),
                "LAST_WRITE channel should reject a second sender");
    }

    @Test
    @TestTransaction
    void lastWriteRejectionMessageIdentifiesCurrentWriter() {
        tools.createChannel("lw-5", "LAST_WRITE channel", "LAST_WRITE", null);
        tools.sendMessage("lw-5", "alice", "status", "alice owns this", null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tools.sendMessage("lw-5", "bob", "status", "bob tries", null, null));

        assertTrue(ex.getMessage().contains("alice"),
                "rejection message should identify the current writer");
    }

    @Test
    @TestTransaction
    void appendChannelAllowsMultipleSendersUnaffected() {
        tools.createChannel("append-lw", "APPEND channel", "APPEND", null);
        MessageResult m1 = tools.sendMessage("append-lw", "alice", "status", "first", null, null);
        MessageResult m2 = tools.sendMessage("append-lw", "bob", "status", "second", null, null);

        // APPEND creates distinct messages, different IDs
        assertNotEquals(m1.messageId(), m2.messageId(),
                "APPEND channel should not apply LAST_WRITE logic");

        CheckResult messages = tools.checkMessages("append-lw", 0L, 10, null);
        assertEquals(2, messages.messages().size());
    }
}

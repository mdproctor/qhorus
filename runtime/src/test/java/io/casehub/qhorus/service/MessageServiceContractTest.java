package io.casehub.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

public abstract class MessageServiceContractTest {

    // ── Core send/query operations ────────────────────────────────────────────

    protected abstract DispatchResult send(UUID channelId, String sender, MessageType type,
            String content, String correlationId, Long inReplyTo);

    protected abstract Optional<Message> findById(Long id);

    protected abstract List<Message> pollAfter(UUID channelId, Long afterId, int limit);

    // ── Channel/instance setup helpers ────────────────────────────────────────

    /**
     * Persists a channel with the given properties and returns its ID.
     * Each runner uses its own transactional strategy.
     */
    protected abstract UUID persistChannel(boolean paused, String allowedWriters,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes, ChannelSemantic semantic);

    /**
     * Registers an instance with the given capabilities.
     * No-op in runners where instance registration is not needed for the tests.
     */
    protected abstract void persistInstance(String instanceId, List<String> capabilities);

    // ── Convenience channel factory wrappers ──────────────────────────────────

    protected UUID createOpenChannel() {
        return persistChannel(false, null, null, null, ChannelSemantic.APPEND);
    }

    protected UUID createPausedChannel() {
        return persistChannel(true, null, null, null, ChannelSemantic.APPEND);
    }

    protected UUID createAclChannel(String allowedWriters) {
        return persistChannel(false, allowedWriters, null, null, ChannelSemantic.APPEND);
    }

    protected UUID createLastWriteChannel() {
        return persistChannel(false, null, null, null, ChannelSemantic.LAST_WRITE);
    }

    // ── Baseline contract tests ───────────────────────────────────────────────

    @Test
    void send_returnsPersistedMessage() {
        UUID ch = UUID.randomUUID();
        DispatchResult m = send(ch, "alice", MessageType.COMMAND, "hello", "corr-1", null);
        assertNotNull(m.messageId());
        assertEquals("alice", m.sender());
        assertEquals(MessageType.COMMAND, m.type());
    }

    @Test
    void findById_returnsMessage_whenExists() {
        UUID ch = UUID.randomUUID();
        DispatchResult sent = send(ch, "alice", MessageType.STATUS, "content", null, null);
        Optional<Message> found = findById(sent.messageId());
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().sender);
    }

    @Test
    void findById_returnsEmpty_whenAbsent() {
        assertTrue(findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void pollAfter_excludesEventType() {
        UUID ch = UUID.randomUUID();
        send(ch, "alice", MessageType.COMMAND, "req", null, null);
        send(ch, "system", MessageType.EVENT, null, null, null);
        List<Message> polled = pollAfter(ch, 0L, 20);
        assertTrue(polled.stream().noneMatch(m -> m.messageType == MessageType.EVENT));
    }

    @Test
    void pollAfter_returnsOnlyAfterCursor() {
        UUID ch = UUID.randomUUID();
        DispatchResult first = send(ch, "alice", MessageType.COMMAND, "first", null, null);
        send(ch, "alice", MessageType.STATUS, "second", null, null);
        List<Message> polled = pollAfter(ch, first.messageId(), 20);
        assertTrue(polled.stream().noneMatch(m -> m.id <= first.messageId()));
    }

    // ── Enforcement contract tests ────────────────────────────────────────────

    @Test
    void paused_channel_rejects_send() {
        UUID channelId = createPausedChannel();
        assertThrows(Exception.class,
                () -> send(channelId, "alice", MessageType.COMMAND, "hi", null, null));
    }

    @Test
    void acl_rejects_unauthorised_sender() {
        UUID channelId = createAclChannel("bob");
        assertThrows(Exception.class,
                () -> send(channelId, "alice", MessageType.COMMAND, "hi", null, null));
    }

    @Test
    void acl_permits_sender_by_name() {
        UUID channelId = createAclChannel("alice");
        DispatchResult result = send(channelId, "alice", MessageType.COMMAND, "hi", "corr-acl", null);
        assertNotNull(result.messageId());
    }

    @Test
    void last_write_same_sender_updates_in_place() {
        UUID channelId = createLastWriteChannel();
        DispatchResult first = send(channelId, "alice", MessageType.STATUS, "v1", null, null);
        DispatchResult second = send(channelId, "alice", MessageType.STATUS, "v2", null, null);
        assertEquals(first.messageId(), second.messageId());
        Optional<Message> msg = findById(second.messageId());
        assertTrue(msg.isPresent());
        assertEquals("v2", msg.get().content);
    }

    @Test
    void last_write_different_sender_throws() {
        UUID channelId = createLastWriteChannel();
        send(channelId, "alice", MessageType.STATUS, "v1", null, null);
        assertThrows(Exception.class,
                () -> send(channelId, "bob", MessageType.STATUS, "v2", null, null));
    }
}

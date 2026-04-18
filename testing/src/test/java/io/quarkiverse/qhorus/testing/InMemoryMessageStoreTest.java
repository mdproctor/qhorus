package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

class InMemoryMessageStoreTest {

    private InMemoryMessageStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessageStore();
    }

    private Message makeMessage(UUID channelId, String sender, MessageType type) {
        Message m = new Message();
        m.channelId = channelId;
        m.sender = sender;
        m.messageType = type;
        m.content = "test content";
        return m;
    }

    @Test
    void put_assignsId_whenIdIsNull() {
        UUID channelId = UUID.randomUUID();
        Message m = makeMessage(channelId, "agent-a", MessageType.REQUEST);
        Message saved = store.put(m);
        assertNotNull(saved.id);
    }

    @Test
    void put_idsAreMonotonicallyIncreasing() {
        UUID channelId = UUID.randomUUID();
        Message m1 = store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        Message m2 = store.put(makeMessage(channelId, "b", MessageType.RESPONSE));
        assertTrue(m2.id > m1.id);
    }

    @Test
    void put_preservesExistingId() {
        Message m = makeMessage(UUID.randomUUID(), "a", MessageType.REQUEST);
        m.id = 999L;
        store.put(m);
        assertTrue(store.find(999L).isPresent());
    }

    @Test
    void find_returnsMessage_whenPresent() {
        Message m = store.put(makeMessage(UUID.randomUUID(), "a", MessageType.REQUEST));
        assertTrue(store.find(m.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertTrue(store.find(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void scan_forChannel_returnsAllInChannel() {
        UUID channelId = UUID.randomUUID();
        store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        store.put(makeMessage(channelId, "b", MessageType.RESPONSE));
        store.put(makeMessage(UUID.randomUUID(), "c", MessageType.EVENT));

        List<Message> results = store.scan(MessageQuery.forChannel(channelId));
        assertEquals(2, results.size());
    }

    @Test
    void scan_poll_respectsAfterIdAndLimit() {
        UUID channelId = UUID.randomUUID();
        Message m1 = store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        Message m2 = store.put(makeMessage(channelId, "b", MessageType.REQUEST));
        store.put(makeMessage(channelId, "c", MessageType.REQUEST));

        // afterId = m1.id means we want messages with id > m1.id, limited to 1
        List<Message> results = store.scan(MessageQuery.poll(channelId, m1.id, 1));
        assertEquals(1, results.size());
        assertEquals(m2.id, results.get(0).id);
    }

    @Test
    void scan_excludeTypes_filtersOut() {
        UUID channelId = UUID.randomUUID();
        store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        store.put(makeMessage(channelId, "b", MessageType.EVENT));

        List<Message> results = store.scan(
                MessageQuery.builder().channelId(channelId).excludeTypes(List.of(MessageType.EVENT)).build());
        assertEquals(1, results.size());
        assertEquals(MessageType.REQUEST, results.get(0).messageType);
    }

    @Test
    void scan_replies_findsInReplyTo() {
        UUID channelId = UUID.randomUUID();
        Message original = store.put(makeMessage(channelId, "a", MessageType.REQUEST));

        Message reply = makeMessage(channelId, "b", MessageType.RESPONSE);
        reply.inReplyTo = original.id;
        store.put(reply);

        store.put(makeMessage(channelId, "c", MessageType.EVENT));

        List<Message> results = store.scan(MessageQuery.replies(channelId, original.id));
        assertEquals(1, results.size());
        assertEquals(original.id, results.get(0).inReplyTo);
    }

    @Test
    void scan_contentPattern_matchesCaseInsensitive() {
        UUID channelId = UUID.randomUUID();
        Message m = makeMessage(channelId, "a", MessageType.REQUEST);
        m.content = "Hello World";
        store.put(m);

        List<Message> results = store.scan(
                MessageQuery.builder().channelId(channelId).contentPattern("hello").build());
        assertEquals(1, results.size());
    }

    @Test
    void deleteAll_removesAllMessagesInChannel() {
        UUID channelId = UUID.randomUUID();
        store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        store.put(makeMessage(channelId, "b", MessageType.REQUEST));
        store.put(makeMessage(UUID.randomUUID(), "c", MessageType.REQUEST));

        store.deleteAll(channelId);
        assertEquals(0, store.countByChannel(channelId));
        // The other channel message should remain
        assertEquals(1, store.scan(MessageQuery.builder().build()).size());
    }

    @Test
    void delete_removesById() {
        Message m = store.put(makeMessage(UUID.randomUUID(), "a", MessageType.REQUEST));
        store.delete(m.id);
        assertTrue(store.find(m.id).isEmpty());
    }

    @Test
    void countByChannel_returnsCorrectCount() {
        UUID channelId = UUID.randomUUID();
        store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        store.put(makeMessage(channelId, "b", MessageType.RESPONSE));
        store.put(makeMessage(UUID.randomUUID(), "c", MessageType.EVENT));

        assertEquals(2, store.countByChannel(channelId));
    }

    @Test
    void clear_removesAllAndResetsCounter() {
        UUID channelId = UUID.randomUUID();
        Message first = store.put(makeMessage(channelId, "a", MessageType.REQUEST));
        store.clear();

        assertEquals(0, store.scan(MessageQuery.builder().build()).size());

        // After clear, IDs restart from 1
        Message second = store.put(makeMessage(channelId, "b", MessageType.REQUEST));
        assertEquals(1L, second.id);
    }
}

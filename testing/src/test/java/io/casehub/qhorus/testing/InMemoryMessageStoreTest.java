package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.casehub.qhorus.testing.contract.MessageStoreContractTest;

class InMemoryMessageStoreTest extends MessageStoreContractTest {
    private final InMemoryMessageStore store = new InMemoryMessageStore();

    @Override
    protected Message put(Message m) {
        return store.put(m);
    }

    @Override
    protected Optional<Message> find(Long id) {
        return store.find(id);
    }

    @Override
    protected List<Message> scan(MessageQuery q) {
        return store.scan(q);
    }

    @Override
    protected Map<UUID, Long> countAllByChannel() {
        return store.countAllByChannel();
    }

    @Override
    protected List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return store.distinctSendersByChannel(channelId, excludedType);
    }

    @Override
    protected long count(MessageQuery q) {
        return store.count(q);
    }

    @Override
    protected Optional<Message> findLastMessage(UUID channelId) {
        return store.findLastMessage(channelId);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void scan_poll_respectsAfterIdAndLimit() {
        UUID channelId = UUID.randomUUID();
        Message m1 = store.put(msg(channelId, "a", MessageType.COMMAND));
        Message m2 = store.put(msg(channelId, "b", MessageType.COMMAND));
        store.put(msg(channelId, "c", MessageType.COMMAND));

        List<Message> results = store.scan(MessageQuery.poll(channelId, m1.id, 1));
        assertEquals(1, results.size());
        assertEquals(m2.id, results.get(0).id);
    }

    @Test
    void deleteAll_removesAllMessagesInChannel() {
        UUID channelId = UUID.randomUUID();
        store.put(msg(channelId, "a", MessageType.COMMAND));
        store.put(msg(channelId, "b", MessageType.COMMAND));
        UUID otherId = UUID.randomUUID();
        store.put(msg(otherId, "c", MessageType.COMMAND));

        store.deleteAll(channelId);
        assertEquals(0, store.countByChannel(channelId));
        assertEquals(1, store.countByChannel(otherId));
    }

    @Test
    void countByChannel_returnsCorrectCount() {
        UUID channelId = UUID.randomUUID();
        store.put(msg(channelId, "a", MessageType.COMMAND));
        store.put(msg(channelId, "b", MessageType.RESPONSE));
        store.put(msg(UUID.randomUUID(), "c", MessageType.EVENT));

        assertEquals(2, store.countByChannel(channelId));
    }

    @Test
    void scan_descending_returnsNewestFirst() {
        UUID channelId = UUID.randomUUID();
        Message m1 = store.put(msg(channelId, "a", MessageType.COMMAND));
        Message m2 = store.put(msg(channelId, "b", MessageType.COMMAND));
        Message m3 = store.put(msg(channelId, "c", MessageType.COMMAND));

        List<Message> results = store.scan(
                MessageQuery.builder().channelId(channelId).descending(true).limit(2).build());
        assertEquals(2, results.size());
        assertEquals(m3.id, results.get(0).id);
        assertEquals(m2.id, results.get(1).id);
    }

    @Test
    void scan_recent_globalDescending_returnsNewestAcrossChannels() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        store.put(msg(ch1, "a", MessageType.COMMAND));  // id 1
        store.put(msg(ch2, "b", MessageType.COMMAND));  // id 2
        store.put(msg(ch1, "c", MessageType.COMMAND));  // id 3

        List<Message> results = store.scan(MessageQuery.recent(2));
        assertEquals(2, results.size());
        // newest first: id 3, id 2
        assertTrue(results.get(0).id > results.get(1).id);
    }
}

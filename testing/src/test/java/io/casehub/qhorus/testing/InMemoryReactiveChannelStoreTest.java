package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.testing.contract.ChannelStoreContractTest;

class InMemoryReactiveChannelStoreTest extends ChannelStoreContractTest {
    private final InMemoryReactiveChannelStore store = new InMemoryReactiveChannelStore();

    @Override protected Channel put(Channel c) { return store.put(c).await().indefinitely(); }
    @Override protected Optional<Channel> find(UUID id) { return store.find(id).await().indefinitely(); }
    @Override protected Optional<Channel> findByName(String n) { return store.findByName(n).await().indefinitely(); }
    @Override protected List<Channel> scan(ChannelQuery q) { return store.scan(q).await().indefinitely(); }
    @Override protected void delete(UUID id) { store.delete(id).await().indefinitely(); }
    @Override protected void updateLastActivity(UUID chId, String tId) { store.updateLastActivity(chId, tId).await().indefinitely(); }
    @Override protected List<Channel> findByIds(Collection<UUID> ids) { return store.findByIds(ids).await().indefinitely(); }
    @Override protected void reset() { store.clear(); }

    @Test
    void scan_bySemantic_returnsMatching() {
        store.put(channel("barrier-" + UUID.randomUUID(), ChannelSemantic.BARRIER)).await().indefinitely();
        store.put(channel("append-" + UUID.randomUUID(), ChannelSemantic.APPEND)).await().indefinitely();
        List<Channel> results = store.scan(ChannelQuery.bySemantic(ChannelSemantic.BARRIER)).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(ChannelSemantic.BARRIER, results.get(0).semantic());
    }

    @Test
    void delete_nonexistent_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID()).await().indefinitely());
    }

    @Test
    void clear_removesAll() {
        store.put(channel("temp-" + UUID.randomUUID(), ChannelSemantic.APPEND)).await().indefinitely();
        store.clear();
        assertTrue(store.scan(ChannelQuery.all()).await().indefinitely().isEmpty());
    }
}

package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

class InMemoryChannelStoreTest {

    private InMemoryChannelStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryChannelStore();
    }

    @Test
    void put_assignsUuid_whenIdIsNull() {
        Channel ch = new Channel();
        ch.name = "test";
        ch.semantic = ChannelSemantic.APPEND;
        Channel saved = store.put(ch);
        assertNotNull(saved.id);
    }

    @Test
    void put_preservesExistingId() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "preset";
        ch.semantic = ChannelSemantic.COLLECT;
        UUID expected = ch.id;
        Channel saved = store.put(ch);
        assertEquals(expected, saved.id);
    }

    @Test
    void find_returnsChannel_whenPresent() {
        Channel ch = new Channel();
        ch.name = "found";
        ch.semantic = ChannelSemantic.APPEND;
        store.put(ch);
        assertTrue(store.find(ch.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByName_returnsChannel() {
        Channel ch = new Channel();
        ch.name = "findme";
        ch.semantic = ChannelSemantic.COLLECT;
        store.put(ch);
        assertTrue(store.findByName("findme").isPresent());
        assertEquals("findme", store.findByName("findme").get().name);
    }

    @Test
    void findByName_returnsEmpty_whenNoMatch() {
        assertTrue(store.findByName("nosuch").isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        Channel ch1 = new Channel();
        ch1.name = "a";
        ch1.semantic = ChannelSemantic.APPEND;
        store.put(ch1);

        Channel ch2 = new Channel();
        ch2.name = "b";
        ch2.semantic = ChannelSemantic.COLLECT;
        store.put(ch2);

        assertEquals(2, store.scan(ChannelQuery.all()).size());
    }

    @Test
    void scan_pausedOnly_returnsOnlyPaused() {
        Channel active = new Channel();
        active.name = "active";
        active.paused = false;
        active.semantic = ChannelSemantic.APPEND;
        store.put(active);

        Channel paused = new Channel();
        paused.name = "paused";
        paused.paused = true;
        paused.semantic = ChannelSemantic.APPEND;
        store.put(paused);

        List<Channel> results = store.scan(ChannelQuery.pausedOnly());
        assertEquals(1, results.size());
        assertEquals("paused", results.get(0).name);
    }

    @Test
    void scan_bySemantic_returnsMatching() {
        Channel barrier = new Channel();
        barrier.name = "barrier-ch";
        barrier.semantic = ChannelSemantic.BARRIER;
        store.put(barrier);

        Channel append = new Channel();
        append.name = "append-ch";
        append.semantic = ChannelSemantic.APPEND;
        store.put(append);

        List<Channel> results = store.scan(ChannelQuery.bySemantic(ChannelSemantic.BARRIER));
        assertEquals(1, results.size());
        assertEquals("barrier-ch", results.get(0).name);
    }

    @Test
    void delete_removesChannel() {
        Channel ch = new Channel();
        ch.name = "bye";
        ch.semantic = ChannelSemantic.APPEND;
        store.put(ch);
        store.delete(ch.id);
        assertTrue(store.find(ch.id).isEmpty());
    }

    @Test
    void delete_nonexistent_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID()));
    }

    @Test
    void clear_removesAll() {
        Channel ch = new Channel();
        ch.name = "temp";
        ch.semantic = ChannelSemantic.APPEND;
        store.put(ch);
        store.clear();
        assertTrue(store.scan(ChannelQuery.all()).isEmpty());
    }
}

package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.store.query.InstanceQuery;

class InMemoryInstanceStoreTest {

    private InMemoryInstanceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryInstanceStore();
    }

    private Instance makeInstance(String instanceId, String status) {
        Instance i = new Instance();
        i.instanceId = instanceId;
        i.status = status;
        i.lastSeen = Instant.now();
        return i;
    }

    @Test
    void put_assignsUuid_whenIdIsNull() {
        Instance i = makeInstance("agent-1", "online");
        Instance saved = store.put(i);
        assertNotNull(saved.id);
    }

    @Test
    void put_preservesExistingId() {
        Instance i = makeInstance("agent-1", "online");
        i.id = UUID.randomUUID();
        UUID expected = i.id;
        store.put(i);
        assertEquals(expected, store.find(expected).get().id);
    }

    @Test
    void find_returnsInstance_whenPresent() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);
        assertTrue(store.find(i.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByInstanceId_returnsInstance() {
        store.put(makeInstance("agent-x", "online"));
        assertTrue(store.findByInstanceId("agent-x").isPresent());
        assertEquals("agent-x", store.findByInstanceId("agent-x").get().instanceId);
    }

    @Test
    void findByInstanceId_returnsEmpty_whenNoMatch() {
        assertTrue(store.findByInstanceId("nobody").isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        store.put(makeInstance("a", "online"));
        store.put(makeInstance("b", "offline"));
        assertEquals(2, store.scan(InstanceQuery.all()).size());
    }

    @Test
    void scan_online_returnsOnlyOnline() {
        store.put(makeInstance("a", "online"));
        store.put(makeInstance("b", "offline"));

        List<Instance> results = store.scan(InstanceQuery.online());
        assertEquals(1, results.size());
        assertEquals("online", results.get(0).status);
    }

    @Test
    void scan_byCapability_returnsMatchingInstances() {
        Instance i1 = makeInstance("agent-1", "online");
        store.put(i1);
        store.putCapabilities(i1.id, List.of("summarize", "translate"));

        Instance i2 = makeInstance("agent-2", "online");
        store.put(i2);
        store.putCapabilities(i2.id, List.of("translate"));

        List<Instance> results = store.scan(InstanceQuery.byCapability("summarize"));
        assertEquals(1, results.size());
        assertEquals("agent-1", results.get(0).instanceId);
    }

    @Test
    void scan_staleOlderThan_returnsOnlyStale() {
        Instance fresh = makeInstance("fresh", "online");
        fresh.lastSeen = Instant.now();
        store.put(fresh);

        Instance stale = makeInstance("stale", "online");
        stale.lastSeen = Instant.now().minusSeconds(3600);
        store.put(stale);

        Instant threshold = Instant.now().minusSeconds(1800);
        List<Instance> results = store.scan(InstanceQuery.staleOlderThan(threshold));
        assertEquals(1, results.size());
        assertEquals("stale", results.get(0).instanceId);
    }

    @Test
    void putCapabilities_replacesExistingList() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);

        store.putCapabilities(i.id, List.of("a", "b"));
        store.putCapabilities(i.id, List.of("c"));

        assertEquals(List.of("c"), store.findCapabilities(i.id));
    }

    @Test
    void deleteCapabilities_removesAll() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);
        store.putCapabilities(i.id, List.of("a", "b"));
        store.deleteCapabilities(i.id);
        assertTrue(store.findCapabilities(i.id).isEmpty());
    }

    @Test
    void findCapabilities_returnsEmptyList_whenNoneRegistered() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);
        assertTrue(store.findCapabilities(i.id).isEmpty());
    }

    @Test
    void delete_removesInstanceAndCapabilities() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);
        store.putCapabilities(i.id, List.of("x"));

        store.delete(i.id);

        assertTrue(store.find(i.id).isEmpty());
        assertTrue(store.findCapabilities(i.id).isEmpty());
    }

    @Test
    void clear_removesAll() {
        Instance i = makeInstance("agent-1", "online");
        store.put(i);
        store.putCapabilities(i.id, List.of("x"));

        store.clear();

        assertTrue(store.scan(InstanceQuery.all()).isEmpty());
        assertTrue(store.findCapabilities(i.id).isEmpty());
    }
}

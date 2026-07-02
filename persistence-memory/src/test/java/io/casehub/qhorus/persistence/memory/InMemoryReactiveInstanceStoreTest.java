package io.casehub.qhorus.persistence.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.casehub.qhorus.persistence.memory.contract.InstanceStoreContractTest;

class InMemoryReactiveInstanceStoreTest extends InstanceStoreContractTest {
    private final InMemoryReactiveInstanceStore store = new InMemoryReactiveInstanceStore();

    @Override protected Instance put(Instance i) { return store.put(i).await().indefinitely(); }
    @Override protected Optional<Instance> find(UUID id) { return store.find(id).await().indefinitely(); }
    @Override protected Optional<Instance> findByInstanceId(String iId) { return store.findByInstanceId(iId).await().indefinitely(); }
    @Override protected List<Instance> scan(InstanceQuery q) { return store.scan(q).await().indefinitely(); }
    @Override protected void reset() { store.clear(); }

    @Test
    void scan_byCapability_returnsMatchingInstances() {
        Instance i1 = store.put(instance("rx-cap-a-" + UUID.randomUUID())).await().indefinitely();
        store.putCapabilities(i1.id(), List.of("summarize", "translate")).await().indefinitely();

        Instance i2 = store.put(instance("rx-cap-b-" + UUID.randomUUID())).await().indefinitely();
        store.putCapabilities(i2.id(), List.of("translate")).await().indefinitely();

        List<Instance> results = store.scan(InstanceQuery.byCapability("summarize")).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(i1.instanceId(), results.get(0).instanceId());
    }

    @Test
    void scan_staleOlderThan_returnsOnlyStale() {
        store.put(instance("rx-fresh-" + UUID.randomUUID())).await().indefinitely();
        Instance stale = store.put(instance("rx-stale-" + UUID.randomUUID())
                .toBuilder().lastSeen(Instant.now().minusSeconds(3600)).build()).await().indefinitely();

        Instant        threshold = Instant.now().minusSeconds(1800);
        List<Instance> results   = store.scan(InstanceQuery.staleOlderThan(threshold)).await().indefinitely();
        assertEquals(1, results.size());
        assertEquals(stale.instanceId(), results.get(0).instanceId());
    }

    @Test
    void putCapabilities_replacesExistingList() {
        Instance i = store.put(instance("rx-cap-replace-" + UUID.randomUUID())).await().indefinitely();
        store.putCapabilities(i.id(), List.of("a", "b")).await().indefinitely();
        store.putCapabilities(i.id(), List.of("c")).await().indefinitely();
        assertEquals(List.of("c"), store.findCapabilities(i.id()).await().indefinitely());
    }

    @Test
    void deleteCapabilities_removesAll() {
        Instance i = store.put(instance("rx-cap-del-" + UUID.randomUUID())).await().indefinitely();
        store.putCapabilities(i.id(), List.of("a", "b")).await().indefinitely();
        store.deleteCapabilities(i.id()).await().indefinitely();
        assertTrue(store.findCapabilities(i.id()).await().indefinitely().isEmpty());
    }
}

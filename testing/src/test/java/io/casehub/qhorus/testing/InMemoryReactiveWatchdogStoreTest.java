package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.testing.contract.WatchdogStoreContractTest;

class InMemoryReactiveWatchdogStoreTest extends WatchdogStoreContractTest {
    private final InMemoryReactiveWatchdogStore store = new InMemoryReactiveWatchdogStore();

    @Override protected Watchdog put(Watchdog w) { return store.put(w).await().indefinitely(); }
    @Override protected Optional<Watchdog> find(UUID id) { return store.find(id).await().indefinitely(); }
    @Override protected List<Watchdog> scan(WatchdogQuery q) { return store.scan(q).await().indefinitely(); }
    @Override protected void delete(UUID id) { store.delete(id).await().indefinitely(); }
    @Override protected void reset() { store.clear(); }

    @Test
    void scan_byConditionType_returnsOnlyMatching() {
        store.put(watchdog("BARRIER_STUCK", "alerts")).await().indefinitely();
        store.put(watchdog("BARRIER_STUCK", "alerts")).await().indefinitely();
        store.put(watchdog("AGENT_STALE", "ops")).await().indefinitely();

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType("BARRIER_STUCK")).await().indefinitely();
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(w -> "BARRIER_STUCK".equals(w.conditionType())));
    }

    @Test
    void put_updatesExistingEntry_whenSameId() {
        Watchdog w = store.put(watchdog("BARRIER_STUCK", "alerts")).await().indefinitely();
        store.put(w.toBuilder().notificationChannel("updated-alerts").build()).await().indefinitely();

        assertEquals("updated-alerts", store.find(w.id()).await().indefinitely().get().notificationChannel());
        assertEquals(1, store.scan(WatchdogQuery.all()).await().indefinitely().size());
    }
}

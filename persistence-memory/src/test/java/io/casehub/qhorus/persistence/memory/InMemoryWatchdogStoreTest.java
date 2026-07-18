package io.casehub.qhorus.persistence.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.persistence.memory.contract.WatchdogStoreContractTest;

class InMemoryWatchdogStoreTest extends WatchdogStoreContractTest {
    private final InMemoryWatchdogStore store = new InMemoryWatchdogStore();

    @Override protected Watchdog put(Watchdog w) { return store.put(w); }
    @Override protected Optional<Watchdog> find(UUID id) { return store.find(id); }
    @Override protected List<Watchdog> scan(WatchdogQuery q) { return store.scan(q); }
    @Override protected void delete(UUID id) { store.delete(id); }
    @Override protected void reset() { store.clear(); }

    @Test
    void scan_byConditionType_returnsOnlyMatching() {
        store.put(watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK, "alerts"));
        store.put(watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK, "alerts"));
        store.put(watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.AGENT_STALE, "ops"));

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType(io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(w -> io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK == w.conditionType()));
    }

    @Test
    void put_updatesExistingEntry_whenSameId() {
        Watchdog w = store.put(watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK, "alerts"));
        Watchdog updated = store.put(w.toBuilder().notificationChannel("updated-alerts").build());

        assertEquals("updated-alerts", store.find(updated.id()).get().notificationChannel());
        assertEquals(1, store.scan(WatchdogQuery.all()).size());
    }
}

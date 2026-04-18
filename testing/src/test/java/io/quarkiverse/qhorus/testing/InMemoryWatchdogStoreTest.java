package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkiverse.qhorus.runtime.watchdog.Watchdog;

class InMemoryWatchdogStoreTest {

    private InMemoryWatchdogStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWatchdogStore();
    }

    private Watchdog makeWatchdog(String conditionType, String targetName, String notificationChannel) {
        Watchdog w = new Watchdog();
        w.conditionType = conditionType;
        w.targetName = targetName;
        w.notificationChannel = notificationChannel;
        w.thresholdSeconds = 300;
        return w;
    }

    @Test
    void put_assignsUuid_whenIdIsNull() {
        Watchdog w = makeWatchdog("BARRIER_STUCK", "my-channel", "alerts");
        Watchdog saved = store.put(w);
        assertNotNull(saved.id);
    }

    @Test
    void put_preservesExistingId() {
        Watchdog w = makeWatchdog("AGENT_STALE", "agent-1", "alerts");
        w.id = UUID.randomUUID();
        UUID expected = w.id;
        store.put(w);
        assertEquals(expected, store.find(expected).get().id);
    }

    @Test
    void find_returnsWatchdog_whenPresent() {
        Watchdog w = makeWatchdog("CHANNEL_IDLE", "ch-1", "ops");
        store.put(w);
        assertTrue(store.find(w.id).isPresent());
        assertEquals("CHANNEL_IDLE", store.find(w.id).get().conditionType);
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        store.put(makeWatchdog("BARRIER_STUCK", "ch-a", "alerts"));
        store.put(makeWatchdog("AGENT_STALE", "agent-b", "alerts"));
        assertEquals(2, store.scan(WatchdogQuery.all()).size());
    }

    @Test
    void scan_byConditionType_returnsOnlyMatching() {
        store.put(makeWatchdog("BARRIER_STUCK", "ch-a", "alerts"));
        store.put(makeWatchdog("BARRIER_STUCK", "ch-b", "alerts"));
        store.put(makeWatchdog("AGENT_STALE", "agent-x", "ops"));

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType("BARRIER_STUCK"));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(w -> "BARRIER_STUCK".equals(w.conditionType)));
    }

    @Test
    void scan_byConditionType_returnsEmpty_whenNoMatch() {
        store.put(makeWatchdog("BARRIER_STUCK", "ch-a", "alerts"));

        List<Watchdog> results = store.scan(WatchdogQuery.byConditionType("QUEUE_DEPTH"));
        assertTrue(results.isEmpty());
    }

    @Test
    void delete_removesWatchdog() {
        Watchdog w = makeWatchdog("APPROVAL_PENDING", "case-1", "ops");
        store.put(w);
        store.delete(w.id);
        assertTrue(store.find(w.id).isEmpty());
    }

    @Test
    void delete_nonexistent_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID()));
    }

    @Test
    void clear_removesAll() {
        store.put(makeWatchdog("BARRIER_STUCK", "ch-a", "alerts"));
        store.put(makeWatchdog("AGENT_STALE", "agent-b", "ops"));
        store.clear();
        assertTrue(store.scan(WatchdogQuery.all()).isEmpty());
    }

    @Test
    void put_updatesExistingEntry_whenSameId() {
        Watchdog w = makeWatchdog("BARRIER_STUCK", "ch-a", "alerts");
        store.put(w);

        w.notificationChannel = "updated-channel";
        store.put(w);

        assertEquals("updated-channel", store.find(w.id).get().notificationChannel);
        assertEquals(1, store.scan(WatchdogQuery.all()).size());
    }
}

package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;

class WatchdogQueryTest {

    private Watchdog watchdog(String conditionType) {
        return Watchdog.builder(conditionType, "test-channel")
                .notificationChannel("alerts").build();
    }

    @Test
    void all_matchesAnyWatchdog() {
        assertTrue(WatchdogQuery.all().matches(watchdog("BARRIER_STUCK")));
    }

    @Test
    void byConditionType_matchesCorrectType() {
        assertTrue(WatchdogQuery.byConditionType("AGENT_STALE").matches(watchdog("AGENT_STALE")));
    }

    @Test
    void byConditionType_doesNotMatchDifferentType() {
        assertFalse(WatchdogQuery.byConditionType("AGENT_STALE").matches(watchdog("BARRIER_STUCK")));
    }

    @Test
    void byConditionType_doesNotMatchNullConditionType() {
        Watchdog w = Watchdog.builder(null, "test-channel")
                .notificationChannel("alerts").build();
        assertFalse(WatchdogQuery.byConditionType("QUEUE_DEPTH").matches(w));
    }

    @Test
    void builder_filtersOnConditionType() {
        WatchdogQuery q = WatchdogQuery.builder().conditionType("BARRIER_STUCK").build();

        assertTrue(q.matches(watchdog("BARRIER_STUCK")));
        assertFalse(q.matches(watchdog("AGENT_STALE")));
    }

    @Test
    void toBuilder_roundTrips() {
        WatchdogQuery original = WatchdogQuery.builder().conditionType("CHANNEL_IDLE").build();
        WatchdogQuery copy = original.toBuilder().build();

        assertTrue(original.matches(watchdog("CHANNEL_IDLE")));
        assertTrue(copy.matches(watchdog("CHANNEL_IDLE")));
    }

    @Test
    void conditionType_accessor_returnsValue() {
        WatchdogQuery q = WatchdogQuery.byConditionType("APPROVAL_PENDING");
        assertEquals("APPROVAL_PENDING", q.conditionType());
    }
}

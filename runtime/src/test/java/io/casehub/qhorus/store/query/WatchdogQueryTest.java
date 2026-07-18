package io.casehub.qhorus.store.query;

import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class WatchdogQueryTest {

    private Watchdog watchdog(WatchdogConditionType conditionType) {
        return Watchdog.builder(conditionType, "test-channel")
                       .notificationChannel("alerts").build();
    }

    @Test
    void all_matchesAnyWatchdog() {
        assertTrue(WatchdogQuery.all().matches(watchdog(WatchdogConditionType.BARRIER_STUCK)));
    }

    @Test
    void byConditionType_matchesCorrectType() {
        assertTrue(WatchdogQuery.byConditionType(WatchdogConditionType.AGENT_STALE).matches(watchdog(WatchdogConditionType.AGENT_STALE)));
    }

    @Test
    void byConditionType_doesNotMatchDifferentType() {
        assertFalse(WatchdogQuery.byConditionType(WatchdogConditionType.AGENT_STALE).matches(watchdog(WatchdogConditionType.BARRIER_STUCK)));
    }

    @Test
    void byConditionType_doesNotMatchNullConditionType() {
        Watchdog w = Watchdog.builder(null, "test-channel")
                             .notificationChannel("alerts").build();
        assertFalse(WatchdogQuery.byConditionType(WatchdogConditionType.QUEUE_DEPTH).matches(w));
    }

    @Test
    void builder_filtersOnConditionType() {
        WatchdogQuery q = WatchdogQuery.builder().conditionType(WatchdogConditionType.BARRIER_STUCK).build();

        assertTrue(q.matches(watchdog(WatchdogConditionType.BARRIER_STUCK)));
        assertFalse(q.matches(watchdog(WatchdogConditionType.AGENT_STALE)));
    }

    @Test
    void toBuilder_roundTrips() {
        WatchdogQuery original = WatchdogQuery.builder().conditionType(WatchdogConditionType.CHANNEL_IDLE).build();
        WatchdogQuery copy     = original.toBuilder().build();

        assertTrue(original.matches(watchdog(WatchdogConditionType.CHANNEL_IDLE)));
        assertTrue(copy.matches(watchdog(WatchdogConditionType.CHANNEL_IDLE)));
    }

    @Test
    void conditionType_accessor_returnsValue() {
        WatchdogQuery q = WatchdogQuery.byConditionType(WatchdogConditionType.APPROVAL_PENDING);
        assertEquals(WatchdogConditionType.APPROVAL_PENDING, q.conditionType());
    }
}

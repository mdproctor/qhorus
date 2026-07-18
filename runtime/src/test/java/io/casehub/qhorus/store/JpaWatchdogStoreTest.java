package io.casehub.qhorus.store;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.WatchdogEnabledProfile;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(WatchdogEnabledProfile.class)
class JpaWatchdogStoreTest {

    @Inject
    WatchdogStore watchdogStore;

    private Watchdog buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType conditionType) {
        return Watchdog.builder(conditionType, "test-target-" + UUID.randomUUID())
                .notificationChannel("alerts").thresholdSeconds(300)
                .createdBy("test-agent").tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                .build();
    }

    @Test
    @TestTransaction
    void put_persistsWatchdogAndAssignsId() {
        Watchdog saved = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE));

        assertNotNull(saved.id());
        assertEquals(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE, saved.conditionType());
    }

    @Test
    @TestTransaction
    void find_returnsWatchdog_whenExists() {
        Watchdog w = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.BARRIER_STUCK));

        Optional<Watchdog> found = watchdogStore.find(w.id());

        assertTrue(found.isPresent());
        assertEquals(w.id(), found.get().id());
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(watchdogStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_all_returnsAllWatchdogs() {
        Watchdog w1 = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.QUEUE_DEPTH));
        Watchdog w2 = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.AGENT_STALE));

        List<Watchdog> results = watchdogStore.scan(WatchdogQuery.all());

        assertTrue(results.stream().anyMatch(wd -> wd.id().equals(w1.id())));
        assertTrue(results.stream().anyMatch(wd -> wd.id().equals(w2.id())));
    }

    @Test
    @TestTransaction
    void scan_byConditionType_returnsMatchingOnly() {
        Watchdog idle  = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE));
        Watchdog stale = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.AGENT_STALE));

        List<Watchdog> results = watchdogStore.scan(WatchdogQuery.byConditionType(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE));

        assertTrue(results.stream().anyMatch(wd -> wd.id().equals(idle.id())));
        assertTrue(results.stream().noneMatch(wd -> wd.id().equals(stale.id())));
    }

    @Test
    @TestTransaction
    void delete_removesWatchdog() {
        Watchdog w = watchdogStore.put(buildWatchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.APPROVAL_PENDING));

        watchdogStore.delete(w.id());

        assertTrue(watchdogStore.find(w.id()).isEmpty());
    }
}

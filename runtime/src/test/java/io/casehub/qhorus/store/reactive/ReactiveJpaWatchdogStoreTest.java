package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.store.ReactiveWatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaWatchdogStoreTest {

    @Inject
    ReactiveWatchdogStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Watchdog w = watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE);
        asserter.assertThat(
                () -> Panache.withTransaction("qhorus", () -> store.put(w)),
                saved -> assertNotNull(saved.id()));
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(UUID.randomUUID()),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void scan_byConditionType_returnsMatching(UniAsserter asserter) {
        Watchdog w1 = watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE);
        Watchdog w2 = watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.QUEUE_DEPTH);
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(w1)))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(w2)))
                .assertThat(
                        () -> store.scan(WatchdogQuery.byConditionType(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE)),
                        results -> {
                            assertEquals(1, ((java.util.List<?>) results).size());
                            assertEquals(io.casehub.qhorus.api.watchdog.WatchdogConditionType.CHANNEL_IDLE, ((java.util.List<Watchdog>) results).get(0).conditionType());
                        });
    }

    @Test
    @RunOnVertxContext
    void delete_removesWatchdog(UniAsserter asserter) {
        Watchdog w = watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType.AGENT_STALE);
        final UUID[] savedId = new UUID[1];
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(w))
                        .invoke(s -> savedId[0] = s.id()))
                .execute(() -> store.delete(savedId[0]))
                .assertThat(
                        () -> store.find(savedId[0]),
                        opt -> assertTrue(opt.isEmpty()));
    }

    private Watchdog watchdog(io.casehub.qhorus.api.watchdog.WatchdogConditionType conditionType) {
        return Watchdog.builder(conditionType, "test-target")
                .notificationChannel("test-alerts").build();
    }
}

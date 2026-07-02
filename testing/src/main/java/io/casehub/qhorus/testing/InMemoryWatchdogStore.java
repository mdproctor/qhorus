package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWatchdogStore implements WatchdogStore {

    private final Map<UUID, Watchdog> store = new LinkedHashMap<>();

    @Override
    public Watchdog put(Watchdog watchdog) {
        UUID id = watchdog.id() != null ? watchdog.id() : UUID.randomUUID();
        Instant createdAt = watchdog.createdAt() != null ? watchdog.createdAt() : Instant.now();
        if (watchdog.id() == null || watchdog.createdAt() == null) {
            watchdog = new Watchdog(id, watchdog.conditionType(), watchdog.targetName(),
                    watchdog.thresholdSeconds(), watchdog.thresholdCount(), watchdog.notificationChannel(),
                    watchdog.createdBy(), watchdog.tenancyId(), createdAt, watchdog.lastFiredAt());
        }
        store.put(watchdog.id(), watchdog);
        return watchdog;
    }

    @Override
    public Optional<Watchdog> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Watchdog> scan(WatchdogQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public void delete(UUID id) {
        store.remove(id);
    }

    /** All watchdogs with no tenant filter — for cross-tenant delegation. */
    List<Watchdog> scanAll() {
        return List.copyOf(store.values());
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
    }
}

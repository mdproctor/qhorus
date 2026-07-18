package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWatchdogStore implements WatchdogStore {

    private final Map<UUID, Watchdog> store = new ConcurrentHashMap<>();

    @Override
    public Watchdog put(Watchdog watchdog) {
        UUID    id        = watchdog.id() != null ? watchdog.id() : UUID.randomUUID();
        Instant createdAt = watchdog.createdAt() != null ? watchdog.createdAt() : Instant.now();
        if (watchdog.id() == null || watchdog.createdAt() == null) {
            watchdog = watchdog.toBuilder().id(id).createdAt(createdAt).build();
        }
        store.put(watchdog.id(), watchdog);
        return watchdog;}

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

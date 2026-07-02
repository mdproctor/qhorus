package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.smallrye.mutiny.Uni;

public interface ReactiveWatchdogStore {
    Uni<Watchdog> put(Watchdog watchdog);

    Uni<Optional<Watchdog>> find(UUID id);

    Uni<List<Watchdog>> scan(WatchdogQuery query);

    Uni<Void> delete(UUID id);
}

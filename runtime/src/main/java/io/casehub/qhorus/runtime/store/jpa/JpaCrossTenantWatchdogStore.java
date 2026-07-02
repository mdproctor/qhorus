package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.runtime.watchdog.WatchdogEntity;

@ApplicationScoped
public class JpaCrossTenantWatchdogStore implements CrossTenantWatchdogStore {

    @Override
    public List<Watchdog> listAll() {
        return WatchdogEntity.<WatchdogEntity>listAll()
                .stream().map(WatchdogEntity::toDomain).toList();
    }
}

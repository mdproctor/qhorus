package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.runtime.watchdog.WatchdogEntity;

@ApplicationScoped
public class JpaCrossTenantWatchdogStore implements CrossTenantWatchdogStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public List<Watchdog> listAll() {
        return em.createQuery("SELECT e FROM Watchdog e", WatchdogEntity.class)
                .getResultList().stream().map(WatchdogEntity::toDomain).toList();
    }
}

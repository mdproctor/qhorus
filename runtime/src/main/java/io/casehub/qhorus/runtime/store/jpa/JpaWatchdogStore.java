package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.runtime.watchdog.WatchdogEntity;

@ApplicationScoped
public class JpaWatchdogStore implements WatchdogStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public Watchdog put(Watchdog watchdog) {
        WatchdogEntity entity = WatchdogEntity.fromDomain(watchdog);
        if (entity.id != null) {
            entity = WatchdogEntity.getEntityManager().merge(entity);
            WatchdogEntity.flush();
        } else {
            entity.persistAndFlush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Watchdog> find(UUID id) {
        return WatchdogEntity.<WatchdogEntity>find("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId())
                             .<WatchdogEntity>firstResultOptional()
                             .map(WatchdogEntity::toDomain);
    }

    @Override
    public List<Watchdog> scan(WatchdogQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Watchdog WHERE tenancyId = ?1");
        List<Object> params = new ArrayList<>();
        params.add(currentPrincipal.tenancyId());
        int idx = 2;

        if (q.conditionType() != null) {
            jpql.append(" AND conditionType = ?").append(idx++);
            params.add(q.conditionType().name());
        }

        List<WatchdogEntity> entities = WatchdogEntity.list(jpql.toString(), params.toArray());
        return entities.stream().map(WatchdogEntity::toDomain).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        WatchdogEntity.delete("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId());
    }
}

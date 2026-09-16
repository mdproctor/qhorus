package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public Watchdog put(Watchdog watchdog) {
        WatchdogEntity entity = WatchdogEntity.fromDomain(watchdog);
        if (entity.id != null) {
            entity = em.merge(entity);
            em.flush();
        } else {
            em.persist(entity);
            em.flush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Watchdog> find(UUID id) {
        return em.createQuery("SELECT e FROM Watchdog e WHERE e.id = ?1 AND e.tenancyId = ?2", WatchdogEntity.class)
                             .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId())
                             .getResultStream().findFirst()
                             .map(WatchdogEntity::toDomain);
    }

    @Override
    public List<Watchdog> scan(WatchdogQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Watchdog e WHERE e.tenancyId = ?1");
        List<Object> params = new ArrayList<>();
        params.add(currentPrincipal.tenancyId());
        int idx = 2;

        if (q.conditionType() != null) {
            jpql.append(" AND e.conditionType = ?").append(idx++);
            params.add(q.conditionType().name());
        }

        var query = em.createQuery("SELECT e " + jpql.toString(), WatchdogEntity.class);
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
        List<WatchdogEntity> entities = query.getResultList();
        return entities.stream().map(WatchdogEntity::toDomain).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        em.createQuery("DELETE FROM Watchdog e WHERE e.id = ?1 AND e.tenancyId = ?2")
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }
}

package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.query.DataQuery;
import io.casehub.qhorus.runtime.data.ArtefactClaimEntity;
import io.casehub.qhorus.runtime.data.SharedDataEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaDataStore implements DataStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public SharedData put(SharedData data) {
        SharedDataEntity entity = SharedDataEntity.fromDomain(data);
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
    public Optional<SharedData> find(UUID id) {
        return Optional.ofNullable(em.find(SharedDataEntity.class, id))
                .map(SharedDataEntity::toDomain);
    }

    @Override
    public List<SharedData> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<SharedDataEntity> entities = em.createQuery("SELECT e FROM SharedData e WHERE e.id IN ?1", SharedDataEntity.class)
                .setParameter(1, new ArrayList<>(ids)).getResultList();
        return entities.stream().map(SharedDataEntity::toDomain).toList();
    }

    @Override
    public Optional<SharedData> findByKey(String key) {
        return em.createQuery("SELECT e FROM SharedData e WHERE e.key = ?1", SharedDataEntity.class)
                .setParameter(1, key)
                .getResultStream().findFirst()
                .map(SharedDataEntity::toDomain);
    }

    @Override
    public List<SharedData> scan(DataQuery q) {
        StringBuilder jpql = new StringBuilder("FROM SharedData e WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.createdBy() != null) {
            jpql.append(" AND e.createdBy = ?").append(idx++);
            params.add(q.createdBy());
        }
        if (q.complete() != null) {
            jpql.append(" AND e.complete = ?").append(idx++);
            params.add(q.complete());
        }

        var query = em.createQuery("SELECT e " + jpql.toString(), SharedDataEntity.class);
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
        List<SharedDataEntity> entities = query.getResultList();
        return entities.stream().map(SharedDataEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public ArtefactClaim putClaim(ArtefactClaim claim) {
        ArtefactClaimEntity entity = ArtefactClaimEntity.fromDomain(claim);
        em.persist(entity);
        em.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public void deleteClaim(UUID artefactId, UUID instanceId) {
        em.createQuery("DELETE FROM ArtefactClaimEntity e WHERE e.artefactId = ?1 AND e.instanceId = ?2")
                .setParameter(1, artefactId).setParameter(2, instanceId).executeUpdate();
    }

    @Override
    public int countClaims(UUID artefactId) {
        return em.createQuery("SELECT COUNT(e) FROM ArtefactClaimEntity e WHERE e.artefactId = ?1", Long.class)
                .setParameter(1, artefactId).getSingleResult().intValue();
    }

    @Override
    public boolean hasClaim(UUID artefactId, UUID instanceId) {
        return em.createQuery("SELECT COUNT(e) FROM ArtefactClaimEntity e WHERE e.artefactId = ?1 AND e.instanceId = ?2", Long.class)
                .setParameter(1, artefactId).setParameter(2, instanceId).getSingleResult() > 0;
    }


    @Override
    @Transactional
    public void delete(UUID id) {
        em.createQuery("DELETE FROM ArtefactClaimEntity e WHERE e.artefactId = ?1").setParameter(1, id).executeUpdate();
        em.createQuery("DELETE FROM SharedData e WHERE e.id = ?1").setParameter(1, id).executeUpdate();
    }
}

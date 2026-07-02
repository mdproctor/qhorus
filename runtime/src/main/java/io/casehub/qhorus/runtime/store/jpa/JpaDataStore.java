package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.runtime.data.ArtefactClaimEntity;
import io.casehub.qhorus.runtime.data.SharedDataEntity;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.query.DataQuery;

@ApplicationScoped
public class JpaDataStore implements DataStore {

    @Override
    @Transactional
    public SharedData put(SharedData data) {
        SharedDataEntity entity = SharedDataEntity.fromDomain(data);
        if (entity.id != null) {
            entity = SharedDataEntity.getEntityManager().merge(entity);
            SharedDataEntity.flush();
        } else {
            entity.persistAndFlush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<SharedData> find(UUID id) {
        return Optional.ofNullable(SharedDataEntity.<SharedDataEntity>findById(id))
                .map(SharedDataEntity::toDomain);
    }

    @Override
    public List<SharedData> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<SharedDataEntity> entities = SharedDataEntity.list("id IN ?1", new ArrayList<>(ids));
        return entities.stream().map(SharedDataEntity::toDomain).toList();
    }

    @Override
    public Optional<SharedData> findByKey(String key) {
        return SharedDataEntity.<SharedDataEntity>find("key", key)
                .<SharedDataEntity>firstResultOptional()
                .map(SharedDataEntity::toDomain);
    }

    @Override
    public List<SharedData> scan(DataQuery q) {
        StringBuilder jpql = new StringBuilder("FROM SharedData WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.createdBy() != null) {
            jpql.append(" AND createdBy = ?").append(idx++);
            params.add(q.createdBy());
        }
        if (q.complete() != null) {
            jpql.append(" AND complete = ?").append(idx++);
            params.add(q.complete());
        }

        List<SharedDataEntity> entities = SharedDataEntity.list(jpql.toString(), params.toArray());
        return entities.stream().map(SharedDataEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public ArtefactClaim putClaim(ArtefactClaim claim) {
        ArtefactClaimEntity entity = ArtefactClaimEntity.fromDomain(claim);
        entity.persistAndFlush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public void deleteClaim(UUID artefactId, UUID instanceId) {
        ArtefactClaimEntity.delete("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId);
    }

    @Override
    public int countClaims(UUID artefactId) {
        return (int) ArtefactClaimEntity.count("artefactId", artefactId);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        ArtefactClaimEntity.delete("artefactId", id);
        SharedDataEntity.deleteById(id);
    }
}

package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.runtime.instance.CapabilityEntity;
import io.casehub.qhorus.runtime.instance.InstanceEntity;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;

@ApplicationScoped
public class JpaInstanceStore implements InstanceStore {

    @Override
    @Transactional
    public Instance put(Instance instance) {
        InstanceEntity entity = InstanceEntity.fromDomain(instance);
        if (entity.id != null) {
            entity = InstanceEntity.getEntityManager().merge(entity);
            InstanceEntity.flush();
        } else {
            entity.persistAndFlush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Instance> find(UUID id) {
        return Optional.ofNullable(InstanceEntity.<InstanceEntity>findById(id))
                .map(InstanceEntity::toDomain);
    }

    @Override
    public Optional<Instance> findByInstanceId(String instanceId) {
        return InstanceEntity.<InstanceEntity>find("instanceId", instanceId)
                .<InstanceEntity>firstResultOptional()
                .map(InstanceEntity::toDomain);
    }

    @Override
    public List<Instance> scan(InstanceQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Instance WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.status() != null) {
            jpql.append(" AND status = ?").append(idx++);
            params.add(q.status());
        }
        if (q.staleOlderThan() != null) {
            jpql.append(" AND lastSeen < ?").append(idx++);
            params.add(q.staleOlderThan());
        }
        if (q.capability() != null) {
            jpql.append(" AND id IN (SELECT c.instanceId FROM Capability c WHERE c.tag = ?").append(idx++).append(")");
            params.add(q.capability());
        }

        List<InstanceEntity> entities = InstanceEntity.list(jpql.toString(), params.toArray());
        return entities.stream().map(InstanceEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void putCapabilities(UUID instanceId, List<String> tags) {
        CapabilityEntity.delete("instanceId", instanceId);
        for (String tag : tags) {
            CapabilityEntity cap = new CapabilityEntity();
            cap.instanceId = instanceId;
            cap.tag = tag;
            cap.persist();
        }
    }

    @Override
    @Transactional
    public void deleteCapabilities(UUID instanceId) {
        CapabilityEntity.delete("instanceId", instanceId);
    }

    @Override
    public List<String> findCapabilities(UUID instanceId) {
        return CapabilityEntity.<CapabilityEntity> list("instanceId", instanceId)
                               .stream()
                               .map(c -> c.tag)
                               .toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        CapabilityEntity.delete("instanceId", id);
        InstanceEntity.deleteById(id);
    }
}

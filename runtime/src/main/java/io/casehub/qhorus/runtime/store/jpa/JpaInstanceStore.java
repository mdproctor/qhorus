package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.runtime.instance.CapabilityEntity;
import io.casehub.qhorus.runtime.instance.InstanceEntity;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;

@ApplicationScoped
public class JpaInstanceStore implements InstanceStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public Instance put(Instance instance) {
        InstanceEntity entity = InstanceEntity.fromDomain(instance);
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
    public Optional<Instance> find(UUID id) {
        return Optional.ofNullable(em.find(InstanceEntity.class, id))
                .map(InstanceEntity::toDomain);
    }

    @Override
    public Optional<Instance> findByInstanceId(String instanceId) {
        return em.createQuery("SELECT e FROM Instance e WHERE e.instanceId = ?1", InstanceEntity.class)
                .setParameter(1, instanceId)
                .getResultStream().findFirst()
                .map(InstanceEntity::toDomain);
    }

    @Override
    public List<Instance> scan(InstanceQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Instance e WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.status() != null) {
            jpql.append(" AND e.status = ?").append(idx++);
            params.add(q.status());
        }
        if (q.staleOlderThan() != null) {
            jpql.append(" AND e.lastSeen < ?").append(idx++);
            params.add(q.staleOlderThan());
        }
        if (q.capability() != null) {
            jpql.append(" AND e.id IN (SELECT c.instanceId FROM Capability c WHERE c.tag = ?").append(idx++).append(")");
            params.add(q.capability());
        }

        var query = em.createQuery("SELECT e " + jpql.toString(), InstanceEntity.class);
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
        List<InstanceEntity> entities = query.getResultList();
        return entities.stream().map(InstanceEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void putCapabilities(UUID instanceId, List<String> tags) {
        em.createQuery("DELETE FROM Capability e WHERE e.instanceId = ?1").setParameter(1, instanceId).executeUpdate();
        for (String tag : tags) {
            CapabilityEntity cap = new CapabilityEntity();
            cap.instanceId = instanceId;
            cap.tag = tag;
            em.persist(cap);
        }
    }

    @Override
    @Transactional
    public void deleteCapabilities(UUID instanceId) {
        em.createQuery("DELETE FROM Capability e WHERE e.instanceId = ?1").setParameter(1, instanceId).executeUpdate();
    }

    @Override
    public List<String> findCapabilities(UUID instanceId) {
        return em.createQuery("SELECT e FROM Capability e WHERE e.instanceId = ?1", CapabilityEntity.class)
                               .setParameter(1, instanceId)
                               .getResultList().stream()
                               .map(c -> c.tag)
                               .toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        em.createQuery("DELETE FROM Capability e WHERE e.instanceId = ?1").setParameter(1, id).executeUpdate();
        em.createQuery("DELETE FROM Instance e WHERE e.id = ?1").setParameter(1, id).executeUpdate();
    }
}

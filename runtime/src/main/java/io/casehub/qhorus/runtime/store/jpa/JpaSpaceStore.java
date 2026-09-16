package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Space;
import io.casehub.qhorus.api.store.SpaceStore;
import io.casehub.qhorus.runtime.channel.SpaceEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaSpaceStore implements SpaceStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public Space put(Space space) {
        SpaceEntity entity = SpaceEntity.fromDomain(space);
        if (entity.id != null && em.find(SpaceEntity.class, entity.id) != null) {
            entity = em.merge(entity);
            em.flush();
        } else {
            em.persist(entity);
            em.flush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Space> find(UUID id) {
        return em.createQuery("SELECT e FROM Space e WHERE e.id = ?1 AND e.tenancyId = ?2", SpaceEntity.class)
                          .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId())
                          .getResultStream().findFirst()
                          .map(SpaceEntity::toDomain);
    }

    @Override
    public List<Space> findByName(String name) {
        return em.createQuery("SELECT e FROM Space e WHERE e.name = ?1 AND e.tenancyId = ?2", SpaceEntity.class)
                          .setParameter(1, name).setParameter(2, currentPrincipal.tenancyId())
                          .getResultList()
                          .stream()
                          .map(SpaceEntity::toDomain)
                          .toList();
    }

    @Override
    public List<Space> listByParent(UUID parentSpaceId) {
        return em.createQuery("SELECT e FROM Space e WHERE e.parentSpaceId = ?1 AND e.tenancyId = ?2 ORDER BY e.name", SpaceEntity.class)
                          .setParameter(1, parentSpaceId).setParameter(2, currentPrincipal.tenancyId())
                          .getResultList()
                          .stream()
                          .map(SpaceEntity::toDomain)
                          .toList();
    }

    @Override
    public List<Space> listRoots() {
        return em.createQuery("SELECT e FROM Space e WHERE e.parentSpaceId IS NULL AND e.tenancyId = ?1 ORDER BY e.name", SpaceEntity.class)
                          .setParameter(1, currentPrincipal.tenancyId())
                          .getResultList()
                          .stream()
                          .map(SpaceEntity::toDomain)
                          .toList();
    }

    @Override
    public boolean hasChildren(UUID spaceId) {
        return em.createQuery("SELECT COUNT(e) FROM Space e WHERE e.parentSpaceId = ?1 AND e.tenancyId = ?2", Long.class)
                                 .setParameter(1, spaceId).setParameter(2, currentPrincipal.tenancyId()).getSingleResult() > 0;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        em.createQuery("DELETE FROM Space e WHERE e.id = ?1 AND e.tenancyId = ?2")
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    public List<Space> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {return List.of();}
        List<SpaceEntity> entities = em.createQuery("SELECT e FROM Space e WHERE e.id IN ?1 AND e.tenancyId = ?2", SpaceEntity.class)
                                                      .setParameter(1, new java.util.ArrayList<>(ids)).setParameter(2, currentPrincipal.tenancyId()).getResultList();
        return entities.stream().map(SpaceEntity::toDomain).toList();
    }
}

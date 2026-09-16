package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.runtime.gateway.DeliveryCursorEntity;
import io.casehub.qhorus.api.store.DeliveryCursorStore;

@ApplicationScoped
public class JpaDeliveryCursorStore implements DeliveryCursorStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public DeliveryCursor save(DeliveryCursor cursor) {
        DeliveryCursorEntity c = DeliveryCursorEntity.fromDomain(cursor);
        if (c.id == null) {
            em.persist(c);
        } else {
            c = em.merge(c);
        }
        return c.toDomain();
    }

    @Override
    public Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId) {
        return em.createQuery("SELECT e FROM DeliveryCursorEntity e WHERE e.channelId = ?1 AND e.backendId = ?2", DeliveryCursorEntity.class)
                .setParameter(1, channelId).setParameter(2, backendId)
                .getResultStream().findFirst()
                .map(DeliveryCursorEntity::toDomain);
    }

    @Override
    public List<DeliveryCursor> findByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM DeliveryCursorEntity e WHERE e.channelId = ?1", DeliveryCursorEntity.class)
                .setParameter(1, channelId)
                .getResultList()
                .stream().map(DeliveryCursorEntity::toDomain).toList();
    }

    @Override
    public List<DeliveryCursor> findAll() {
        return em.createQuery("SELECT e FROM DeliveryCursorEntity e", DeliveryCursorEntity.class)
                .getResultList()
                .stream().map(DeliveryCursorEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteByChannel(UUID channelId) {
        em.createQuery("DELETE FROM DeliveryCursorEntity e WHERE e.channelId = ?1")
                .setParameter(1, channelId).executeUpdate();
    }
}

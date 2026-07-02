package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.runtime.gateway.DeliveryCursorEntity;
import io.casehub.qhorus.api.store.DeliveryCursorStore;

@ApplicationScoped
public class JpaDeliveryCursorStore implements DeliveryCursorStore {

    @Inject
    DeliveryCursorPanacheRepo repo;

    @Override
    @Transactional
    public DeliveryCursor save(DeliveryCursor cursor) {
        DeliveryCursorEntity c = DeliveryCursorEntity.fromDomain(cursor);
        if (c.id == null) {
            repo.persist(c);
        } else {
            c = repo.getEntityManager().merge(c);
        }
        return c.toDomain();
    }

    @Override
    public Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId) {
        return repo.find("channelId = ?1 AND backendId = ?2", channelId, backendId)
                .<DeliveryCursorEntity>firstResultOptional()
                .map(DeliveryCursorEntity::toDomain);
    }

    @Override
    public List<DeliveryCursor> findByChannel(UUID channelId) {
        return repo.<DeliveryCursorEntity>list("channelId", channelId)
                .stream().map(DeliveryCursorEntity::toDomain).toList();
    }

    @Override
    public List<DeliveryCursor> findAll() {
        return repo.<DeliveryCursorEntity>listAll()
                .stream().map(DeliveryCursorEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteByChannel(UUID channelId) {
        repo.delete("channelId", channelId);
    }
}

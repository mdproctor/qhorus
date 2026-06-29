package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.gateway.DeliveryCursor;
import io.casehub.qhorus.runtime.store.DeliveryCursorStore;

@ApplicationScoped
public class JpaDeliveryCursorStore implements DeliveryCursorStore {

    @Inject
    DeliveryCursorPanacheRepo repo;

    @Override
    @Transactional
    public DeliveryCursor save(DeliveryCursor c) {
        if (c.id == null) {
            repo.persist(c);
        } else {
            c = repo.getEntityManager().merge(c);
        }
        return c;
    }

    @Override
    public Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId) {
        return repo.find("channelId = ?1 AND backendId = ?2", channelId, backendId)
                .firstResultOptional();
    }

    @Override
    public List<DeliveryCursor> findByChannel(UUID channelId) {
        return repo.list("channelId", channelId);
    }

    @Override
    public List<DeliveryCursor> findAll() {
        return repo.listAll();
    }

    @Override
    @Transactional
    public void deleteByChannel(UUID channelId) {
        repo.delete("channelId", channelId);
    }
}

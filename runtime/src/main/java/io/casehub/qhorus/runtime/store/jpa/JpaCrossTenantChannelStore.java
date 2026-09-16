package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;

@ApplicationScoped
public class JpaCrossTenantChannelStore implements CrossTenantChannelStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public List<Channel> listAll() {
        return em.createQuery("SELECT e FROM Channel e", ChannelEntity.class)
                .getResultList().stream().map(ChannelEntity::toDomain).toList();
    }

    @Override
    public Optional<Channel> findById(UUID id) {
        return Optional.ofNullable(em.find(ChannelEntity.class, id))
                .map(ChannelEntity::toDomain);
    }

    @Override
    public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
        return em.createQuery("SELECT e FROM Channel e WHERE e.name = ?1 AND e.tenancyId = ?2", ChannelEntity.class)
                .setParameter(1, name).setParameter(2, tenancyId)
                .getResultStream().findFirst()
                .map(ChannelEntity::toDomain);
    }
}

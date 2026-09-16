package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.CrossTenantChannelSummaryStore;
import io.casehub.qhorus.runtime.channel.ChannelSummaryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class JpaCrossTenantChannelSummaryStore implements CrossTenantChannelSummaryStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public List<ChannelSummary> findAll() {
        return em.createQuery("SELECT e FROM ChannelSummary e", ChannelSummaryEntity.class)
                   .getResultList()
                   .stream().map(ChannelSummaryEntity::toDomain).toList();
    }

    @Override
    public List<ChannelSummary> findWithAutoUpdateConfigured() {
        return em.createQuery("SELECT e FROM ChannelSummary e WHERE e.updateAfterMessages IS NOT NULL OR e.updateAfterSeconds IS NOT NULL", ChannelSummaryEntity.class)
                   .getResultList()
                   .stream().map(ChannelSummaryEntity::toDomain).toList();
    }
}

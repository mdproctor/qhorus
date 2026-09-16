package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import io.casehub.qhorus.runtime.channel.ChannelSummaryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaChannelSummaryStore implements ChannelSummaryStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public ChannelSummary save(ChannelSummary summary) {
        ChannelSummaryEntity e = ChannelSummaryEntity.fromDomain(summary);
        if (e.id == null) {
            em.persist(e);
        } else {
            e = em.merge(e);
        }
        return e.toDomain();
    }

    @Override
    public Optional<ChannelSummary> findByChannelId(UUID channelId) {
        return em.createQuery("SELECT e FROM ChannelSummary e WHERE e.channelId = ?1 AND e.tenancyId = ?2", ChannelSummaryEntity.class)
                   .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId())
                   .getResultStream().findFirst()
                   .map(ChannelSummaryEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByChannelId(UUID channelId) {
        em.createQuery("DELETE FROM ChannelSummary e WHERE e.channelId = ?1 AND e.tenancyId = ?2")
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }
}

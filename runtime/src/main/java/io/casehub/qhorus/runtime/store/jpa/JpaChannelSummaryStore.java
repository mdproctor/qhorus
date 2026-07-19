package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import io.casehub.qhorus.runtime.channel.ChannelSummaryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaChannelSummaryStore implements ChannelSummaryStore {

    @Inject
    ChannelSummaryPanacheRepo repo;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public ChannelSummary save(ChannelSummary summary) {
        ChannelSummaryEntity e = ChannelSummaryEntity.fromDomain(summary);
        if (e.id == null) {
            repo.persist(e);
        } else {
            e = repo.getEntityManager().merge(e);
        }
        return e.toDomain();
    }

    @Override
    public Optional<ChannelSummary> findByChannelId(UUID channelId) {
        return repo.find("channelId = ?1 AND tenancyId = ?2", channelId, currentPrincipal.tenancyId())
                   .firstResultOptional()
                   .map(ChannelSummaryEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByChannelId(UUID channelId) {
        repo.delete("channelId = ?1 AND tenancyId = ?2", channelId, currentPrincipal.tenancyId());
    }
}

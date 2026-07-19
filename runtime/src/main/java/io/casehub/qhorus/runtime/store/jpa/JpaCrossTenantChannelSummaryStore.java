package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.CrossTenantChannelSummaryStore;
import io.casehub.qhorus.runtime.channel.ChannelSummaryEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class JpaCrossTenantChannelSummaryStore implements CrossTenantChannelSummaryStore {

    @Inject
    ChannelSummaryPanacheRepo repo;

    @Override
    public List<ChannelSummary> findAll() {
        return repo.listAll()
                   .stream().map(ChannelSummaryEntity::toDomain).toList();
    }

    @Override
    public List<ChannelSummary> findWithAutoUpdateConfigured() {
        return repo.list("updateAfterMessages IS NOT NULL OR updateAfterSeconds IS NOT NULL")
                   .stream().map(ChannelSummaryEntity::toDomain).toList();
    }
}

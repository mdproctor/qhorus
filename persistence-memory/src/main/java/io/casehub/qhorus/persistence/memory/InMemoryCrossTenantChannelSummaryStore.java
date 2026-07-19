package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.CrossTenantChannelSummaryStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCrossTenantChannelSummaryStore implements CrossTenantChannelSummaryStore {

    @Inject
    InMemoryChannelSummaryStore delegate;

    @Override
    public List<ChannelSummary> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<ChannelSummary> findWithAutoUpdateConfigured() {
        return delegate.findAll().stream()
                .filter(s -> s.updateAfterMessages() != null || s.updateAfterSeconds() != null)
                .toList();
    }
}

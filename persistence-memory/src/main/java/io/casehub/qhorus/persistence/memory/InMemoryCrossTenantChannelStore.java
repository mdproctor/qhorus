package io.casehub.qhorus.persistence.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;

/**
 * In-memory implementation of {@link CrossTenantChannelStore} for use in {@code @QuarkusTest} contexts.
 * Returns all channels with no tenant filter — delegates to {@link InMemoryChannelStore}.
 *
 * <p>Refs #260.
 */
@Alternative
@Priority(1)
@ApplicationScoped
@CrossTenant
public class InMemoryCrossTenantChannelStore implements CrossTenantChannelStore {

    @Inject
    InMemoryChannelStore delegate;

    @Override
    public List<Channel> listAll() {
        return delegate.scanAll();
    }

    @Override
    public Optional<Channel> findById(UUID id) {
        return delegate.findCrossTenant(id);
    }

    @Override
    public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
        return delegate.scanAll().stream()
                .filter(c -> name.equals(c.name) && tenancyId.equals(c.tenancyId))
                .findFirst();
    }
}

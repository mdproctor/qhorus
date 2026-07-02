package io.casehub.qhorus.testing;

import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.watchdog.Watchdog;

/**
 * In-memory implementation of {@link CrossTenantWatchdogStore} for use in {@code @QuarkusTest} contexts.
 * Returns all watchdog registrations with no tenant filter — delegates to {@link InMemoryWatchdogStore}.
 *
 * <p>Refs #260.
 */
@Alternative
@Priority(1)
@ApplicationScoped
@CrossTenant
public class InMemoryCrossTenantWatchdogStore implements CrossTenantWatchdogStore {

    @Inject
    InMemoryWatchdogStore delegate;

    @Override
    public List<Watchdog> listAll() {
        return delegate.scanAll();
    }
}

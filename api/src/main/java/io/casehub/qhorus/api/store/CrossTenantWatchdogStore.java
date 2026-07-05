package io.casehub.qhorus.api.store;

import java.util.List;

import io.casehub.qhorus.api.watchdog.Watchdog;

/**
 * Cross-tenant view of watchdog registrations, used by the scheduler to evaluate
 * all conditions across every tenancy in a single pass.
 *
 * <p>Obtain via CDI injection:
 * <pre>{@code
 *   @Inject CrossTenantWatchdogStore store;
 * }</pre>
 *
 * <p>Refs #260.
 */
public interface CrossTenantWatchdogStore {

    /** All watchdog registrations across every tenancy. */
    List<Watchdog> listAll();
}

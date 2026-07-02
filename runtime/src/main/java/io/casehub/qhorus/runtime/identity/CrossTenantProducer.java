package io.casehub.qhorus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.qualifier.QhorusSystem;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.runtime.store.jpa.JpaCrossTenantChannelStore;
import io.casehub.qhorus.runtime.store.jpa.JpaCrossTenantCommitmentStore;
import io.casehub.qhorus.runtime.store.jpa.JpaCrossTenantMessageStore;
import io.casehub.qhorus.runtime.store.jpa.JpaCrossTenantWatchdogStore;

/**
 * Produces {@code @CrossTenant}-qualified cross-tenant store beans.
 * Guards access via {@link QhorusSystemCurrentPrincipal#isCrossTenantAdmin()} assertion.
 *
 * <p>Refs #260.
 */
@ApplicationScoped
public class CrossTenantProducer {

    @Inject @QhorusSystem QhorusSystemCurrentPrincipal systemPrincipal;
    @Inject JpaCrossTenantChannelStore channelStore;
    @Inject JpaCrossTenantMessageStore messageStore;
    @Inject JpaCrossTenantCommitmentStore commitmentStore;
    @Inject JpaCrossTenantWatchdogStore watchdogStore;

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantChannelStore produceChannelStore() {
        assertCrossTenantAdmin();
        return channelStore;
    }

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantMessageStore produceMessageStore() {
        assertCrossTenantAdmin();
        return messageStore;
    }

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantCommitmentStore produceCommitmentStore() {
        assertCrossTenantAdmin();
        return commitmentStore;
    }

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantWatchdogStore produceWatchdogStore() {
        assertCrossTenantAdmin();
        return watchdogStore;
    }

    private void assertCrossTenantAdmin() {
        if (!systemPrincipal.isCrossTenantAdmin()) {
            throw new IllegalStateException(
                    "QhorusSystemCurrentPrincipal.isCrossTenantAdmin() must return true — qhorus#260");
        }
    }
}

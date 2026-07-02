package io.casehub.qhorus.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CrossTenantProducerTest {

    @Inject @CrossTenant CrossTenantChannelStore channelStore;
    @Inject @CrossTenant CrossTenantMessageStore messageStore;
    @Inject @CrossTenant CrossTenantCommitmentStore commitmentStore;
    @Inject @CrossTenant CrossTenantWatchdogStore watchdogStore;

    @Test
    void crossTenantChannelStore_isProduced() {
        assertThat(channelStore).isNotNull();
    }

    @Test
    void crossTenantMessageStore_isProduced() {
        assertThat(messageStore).isNotNull();
    }

    @Test
    void crossTenantCommitmentStore_isProduced() {
        assertThat(commitmentStore).isNotNull();
    }

    @Test
    void crossTenantWatchdogStore_isProduced() {
        assertThat(watchdogStore).isNotNull();
    }
}

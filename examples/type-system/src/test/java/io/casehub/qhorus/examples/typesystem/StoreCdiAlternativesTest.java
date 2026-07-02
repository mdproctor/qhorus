package io.casehub.qhorus.examples.typesystem;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.casehub.qhorus.api.store.ReactiveDataStore;
import io.casehub.qhorus.api.store.ReactiveInstanceStore;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryCommitmentStore;
import io.casehub.qhorus.persistence.memory.InMemoryDataStore;
import io.casehub.qhorus.persistence.memory.InMemoryInstanceStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveCommitmentStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveDataStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveInstanceStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveMessageStore;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Regression guard: verifies that {@code @Alternative} store beans from
 * {@code casehub-qhorus-testing} are discovered via Jandex and selected by
 * Quarkus CDI when both {@code casehub-qhorus} and {@code casehub-qhorus-testing}
 * are on the test classpath.
 *
 * <p>Prevents the failure mode from claudony#155 / #282: when the reactive stack is
 * enabled ({@code casehub.qhorus.reactive.enabled=true}), {@code ReactiveJpaChannelStore}
 * becomes active. Without {@link InMemoryReactiveChannelStore} listed in
 * {@code quarkus.arc.selected-alternatives}, the JPA store wins and any {@code @QuarkusTest}
 * calling dispatch throws {@code QueryParameterException} (requires a real reactive datasource).
 *
 * <p>This module runs with the reactive stack <em>disabled</em> (H2 only), so
 * {@code ReactiveJpaChannelStore} is excluded by its {@code @IfBuildProperty} gate. The
 * InMemory stores are the only candidates. If Jandex fails to index
 * {@code casehub-qhorus-testing} or the {@code @Alternative} annotations are not honoured
 * for external-jar beans, injection will fail and these tests will catch it.
 */
@QuarkusTest
class StoreCdiAlternativesTest {

    @Inject
    ChannelStore channelStore;

    @Inject
    MessageStore messageStore;

    @Inject
    InstanceStore instanceStore;

    @Inject
    DataStore dataStore;

    @Inject
    CommitmentStore commitmentStore;

    @Inject
    ReactiveChannelStore reactiveChannelStore;

    @Inject
    ReactiveMessageStore reactiveMessageStore;

    @Inject
    ReactiveInstanceStore reactiveInstanceStore;

    @Inject
    ReactiveDataStore reactiveDataStore;

    @Inject
    ReactiveCommitmentStore reactiveCommitmentStore;

    @Test
    void blockingStores_areInMemory() {
        assertThat(channelStore).isInstanceOf(InMemoryChannelStore.class);
        assertThat(messageStore).isInstanceOf(InMemoryMessageStore.class);
        assertThat(instanceStore).isInstanceOf(InMemoryInstanceStore.class);
        assertThat(dataStore).isInstanceOf(InMemoryDataStore.class);
        assertThat(commitmentStore).isInstanceOf(InMemoryCommitmentStore.class);
    }

    // Regression for claudony#155: ReactiveJpaChannelStore (and siblings) were selected
    // over InMemory alternatives when reactive.enabled=true and InMemory stores were
    // absent from quarkus.arc.selected-alternatives in the consumer test config.
    @Test
    void reactiveStores_areInMemory() {
        assertThat(reactiveChannelStore).isInstanceOf(InMemoryReactiveChannelStore.class);
        assertThat(reactiveMessageStore).isInstanceOf(InMemoryReactiveMessageStore.class);
        assertThat(reactiveInstanceStore).isInstanceOf(InMemoryReactiveInstanceStore.class);
        assertThat(reactiveDataStore).isInstanceOf(InMemoryReactiveDataStore.class);
        assertThat(reactiveCommitmentStore).isInstanceOf(InMemoryReactiveCommitmentStore.class);
    }
}

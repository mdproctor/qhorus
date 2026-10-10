package io.casehub.qhorus.runtime.instance;

import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.instance.InstanceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryBackedInstanceManagerTest {

    private InMemoryRegistryService registry;
    private InstanceManager manager;
    private final java.util.ArrayList<io.casehub.qhorus.api.instance.InstanceRegisteredEvent> registeredEvents     = new java.util.ArrayList<>();
    private final java.util.ArrayList<io.casehub.qhorus.api.instance.InstanceDeregisteredEvent> deregisteredEvents = new java.util.ArrayList<>();
    private InstanceManager managerWithEvents;


    @BeforeEach
    void setUp() {
        registry = new InMemoryRegistryService(e -> {});
        manager  = new RegistryBackedInstanceManager(registry);
        registeredEvents.clear();
        deregisteredEvents.clear();
        jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceRegisteredEvent> regEvent =
                new TestEvent<>(registeredEvents::add);
        jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceDeregisteredEvent> deregEvent =
                new TestEvent<>(deregisteredEvents::add);
        managerWithEvents = new RegistryBackedInstanceManager(registry, regEvent, deregEvent);
    }

    @Test
    void registerCreatesRegistryEntry() {
        Instance instance = manager.register("agent-1", "Test agent",
                List.of("code-review"), false);

        assertThat(instance).isNotNull();
        assertThat(instance.instanceId()).isEqualTo("agent-1");
        assertThat(instance.description()).isEqualTo("Test agent");
        assertThat(instance.status()).isEqualTo("online");
        assertThat(instance.readOnly()).isFalse();

        var entry = registry.resolve("agent-1");
        assertThat(entry).isPresent();
        assertThat(entry.get().type()).isEqualTo("agent-instance");
        assertThat(entry.get().metadata().get("capabilities")).isEqualTo("code-review");
    }

    @Test
    void registerUpdatesExistingEntry() {
        manager.register("agent-1", "V1", List.of("review"), false);
        Instance updated = manager.register("agent-1", "V2",
                List.of("review", "code"), true);

        assertThat(updated.description()).isEqualTo("V2");
        assertThat(updated.readOnly()).isTrue();

        var entry = registry.resolve("agent-1");
        assertThat(entry).isPresent();
        assertThat(entry.get().metadata().get("capabilities")).isEqualTo("review,code");
    }

    @Test
    void listInfoReturnsAllRegisteredInstances() {
        manager.register("agent-1", "Agent 1", List.of("review"), false);
        manager.register("agent-2", "Agent 2", List.of("code"), false);

        List<InstanceInfo> infos = manager.listInfo();
        assertThat(infos).hasSize(2);
        assertThat(infos).extracting(InstanceInfo::instanceId)
                .containsExactlyInAnyOrder("agent-1", "agent-2");
    }

    @Test
    void listInfoExcludesNonAgentEntries() {
        manager.register("agent-1", "Agent 1", List.of("review"), false);
        registry.register(new RegistryEntry(
                "service-1", "service", "default", "default",
                Map.of(), Instant.now(), Instant.now(),
                Duration.ofMinutes(5), HealthStatus.HEALTHY));

        List<InstanceInfo> infos = manager.listInfo();
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).instanceId()).isEqualTo("agent-1");
    }

    @Test
    void findInfoByCapability() {
        manager.register("agent-1", "Agent 1", List.of("review", "code"), false);
        manager.register("agent-2", "Agent 2", List.of("code"), false);

        List<InstanceInfo> results = manager.findInfoByCapability("review");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).instanceId()).isEqualTo("agent-1");
    }

    @Test
    void findInfoById() {
        manager.register("agent-1", "Agent 1", List.of("review"), false);

        InstanceInfo info = manager.findInfo("agent-1");
        assertThat(info.instanceId()).isEqualTo("agent-1");
        assertThat(info.description()).isEqualTo("Agent 1");
        assertThat(info.status()).isEqualTo("online");
        assertThat(info.capabilities()).containsExactly("review");
        assertThat(info.readOnly()).isFalse();
        assertThat(info.lastSeen()).isNotNull();
    }

    @Test
    void findInfoThrowsForUnknownInstance() {
        assertThatThrownBy(() -> manager.findInfo("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void findInfoIgnoresNonAgentEntries() {
        registry.register(new RegistryEntry(
                "service-1", "service", "default", "default",
                Map.of(), Instant.now(), Instant.now(),
                Duration.ofMinutes(5), HealthStatus.HEALTHY));

        assertThatThrownBy(() -> manager.findInfo("service-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deregisterRemovesFromRegistry() {
        manager.register("agent-1", "Agent 1", List.of("review"), false);

        manager.deregister("agent-1");

        assertThat(registry.resolve("agent-1")).isEmpty();
        assertThat(manager.listInfo()).isEmpty();
    }

    @Test
    void healthStatusMapsToOnline() {
        manager.register("agent-1", "Agent 1", List.of("review"), false);

        InstanceInfo info = manager.findInfo("agent-1");
        assertThat(info.status()).isEqualTo("online");
    }

    @Test
    void emptyCapabilitiesHandledCorrectly() {
        manager.register("agent-1", "Agent 1", List.of(), false);

        InstanceInfo info = manager.findInfo("agent-1");
        assertThat(info.capabilities()).isEmpty();
    }

    @Test
    void readOnlyFlagPreserved() {
        manager.register("agent-1", "Agent 1", List.of("review"), true);

        InstanceInfo info = manager.findInfo("agent-1");
        assertThat(info.readOnly()).isTrue();
    }

    @Test
    void registerFiresRegisteredEvent() {
        managerWithEvents.register("agent-1", "Agent 1", List.of("review", "code"), false);

        assertThat(registeredEvents).hasSize(1);
        var event = registeredEvents.get(0);
        assertThat(event.instanceId()).isEqualTo("agent-1");
        assertThat(event.previousCapabilities()).isEmpty();
        assertThat(event.currentCapabilities()).containsExactly("review", "code");
    }

    @Test
    void reRegisterFiresEventWithCapabilityDiff() {
        managerWithEvents.register("agent-1", "Agent 1", List.of("review"), false);
        registeredEvents.clear();

        managerWithEvents.register("agent-1", "Agent 1", List.of("review", "code"), false);

        assertThat(registeredEvents).hasSize(1);
        var event = registeredEvents.get(0);
        assertThat(event.previousCapabilities()).containsExactly("review");
        assertThat(event.currentCapabilities()).containsExactly("review", "code");
    }

    @Test
    void deregisterFiresDeregisteredEvent() {
        managerWithEvents.register("agent-1", "Agent 1", List.of("review", "code"), false);

        managerWithEvents.deregister("agent-1");

        assertThat(deregisteredEvents).hasSize(1);
        var event = deregisteredEvents.get(0);
        assertThat(event.instanceId()).isEqualTo("agent-1");
        assertThat(event.capabilities()).containsExactly("review", "code");
    }

    @Test
    void deregisterDoesNotFireEventForUnknownInstance() {
        managerWithEvents.deregister("nope");
        assertThat(deregisteredEvents).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static class TestEvent<T> implements jakarta.enterprise.event.Event<T> {
        private final java.util.function.Consumer<T> sink;

        TestEvent(java.util.function.Consumer<T> sink) {this.sink = sink;}

        @Override
        public void fire(T event)                      {sink.accept(event);}

        @Override
        public <U extends T> java.util.concurrent.CompletionStage<U> fireAsync(U event) {
            sink.accept(event);
            return java.util.concurrent.CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends T> java.util.concurrent.CompletionStage<U> fireAsync(U event, jakarta.enterprise.event.NotificationOptions options) {
            return fireAsync(event);
        }

        @Override
        public jakarta.enterprise.event.Event<T> select(java.lang.annotation.Annotation... qualifiers)                                                               {return this;}

        @Override
        public <U extends T> jakarta.enterprise.event.Event<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers)                               {return (jakarta.enterprise.event.Event<U>) this;}

        @Override
        public <U extends T> jakarta.enterprise.event.Event<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {return (jakarta.enterprise.event.Event<U>) this;}
    }
}

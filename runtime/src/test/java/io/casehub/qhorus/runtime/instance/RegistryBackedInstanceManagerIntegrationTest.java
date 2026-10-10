package io.casehub.qhorus.runtime.instance;

import io.casehub.platform.api.registry.RegistryService;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.instance.InstanceManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@TestProfile(RegistryBackedProfile.class)
class RegistryBackedInstanceManagerIntegrationTest {

    @Inject
    InstanceManager instanceManager;

    @Inject
    RegistryService registryService;

    @Test
    void adapterDisplacesInstanceService() {
        assertThat(instanceManager).isInstanceOf(RegistryBackedInstanceManager.class);
    }

    @Test
    void registerAndFindRoundTrip() {
        Instance instance = instanceManager.register("it-agent-1", "Integration agent",
                List.of("code-review", "testing"), false);

        assertThat(instance.instanceId()).isEqualTo("it-agent-1");
        assertThat(instance.status()).isEqualTo("online");

        InstanceInfo info = instanceManager.findInfo("it-agent-1");
        assertThat(info.instanceId()).isEqualTo("it-agent-1");
        assertThat(info.description()).isEqualTo("Integration agent");
        assertThat(info.capabilities()).containsExactly("code-review", "testing");

        assertThat(registryService.resolve("it-agent-1")).isPresent();
        assertThat(registryService.resolve("it-agent-1").get().type())
                .isEqualTo("agent-instance");
    }

    @Test
    void listInfoVisibleViaRegistryDiscover() {
        instanceManager.register("it-list-1", "Agent A", List.of("review"), false);
        instanceManager.register("it-list-2", "Agent B", List.of("code"), false);

        List<InstanceInfo> infos = instanceManager.listInfo();
        assertThat(infos).extracting(InstanceInfo::instanceId)
                .contains("it-list-1", "it-list-2");
    }

    @Test
    void deregisterRemovesFromRegistryAndManager() {
        instanceManager.register("it-dereg-1", "Temp agent", List.of("temp"), false);
        instanceManager.deregister("it-dereg-1");

        assertThat(registryService.resolve("it-dereg-1")).isEmpty();
        assertThatThrownBy(() -> instanceManager.findInfo("it-dereg-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findInfoByCapabilityFiltersCorrectly() {
        instanceManager.register("it-cap-1", "Review agent", List.of("cap-filter-review"), false);
        instanceManager.register("it-cap-2", "Code agent", List.of("cap-filter-code"), false);

        List<InstanceInfo> results = instanceManager.findInfoByCapability("cap-filter-review");
        assertThat(results).extracting(InstanceInfo::instanceId)
                .containsExactly("it-cap-1");
    }
}

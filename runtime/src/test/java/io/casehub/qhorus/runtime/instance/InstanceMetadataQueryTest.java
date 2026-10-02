package io.casehub.qhorus.runtime.instance;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceMetadataQueryTest {

    @Test
    void byMetadata_matchesInstanceWithKey() {
        Instance inst = Instance.builder("agent-1").id(UUID.randomUUID())
                .metadata(Map.of("project", "qhorus", "family", "casehub")).build();
        InstanceQuery q = InstanceQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(inst)).isTrue();
    }

    @Test
    void byMetadata_rejectsInstanceWithWrongValue() {
        Instance inst = Instance.builder("agent-1").id(UUID.randomUUID())
                .metadata(Map.of("project", "claudony")).build();
        InstanceQuery q = InstanceQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(inst)).isFalse();
    }

    @Test
    void byMetadata_rejectsInstanceWithNoMetadata() {
        Instance inst = Instance.builder("agent-1").id(UUID.randomUUID()).build();
        InstanceQuery q = InstanceQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(inst)).isFalse();
    }

    @Test
    void byMetadata_rejectsInstanceMissingKey() {
        Instance inst = Instance.builder("agent-1").id(UUID.randomUUID())
                .metadata(Map.of("family", "casehub")).build();
        InstanceQuery q = InstanceQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(inst)).isFalse();
    }

    @Test
    void allQuery_matchesInstanceWithMetadata() {
        Instance inst = Instance.builder("agent-1").id(UUID.randomUUID())
                .metadata(Map.of("project", "qhorus")).build();
        assertThat(InstanceQuery.all().matches(inst)).isTrue();
    }
}

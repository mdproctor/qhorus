package io.casehub.qhorus.runtime.instance;

import io.casehub.qhorus.api.instance.Instance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceMetadataTest {

    @Test
    void instanceRecord_metadataDefensiveCopy() {
        var mutable = new LinkedHashMap<String, String>();
        mutable.put("project", "qhorus");
        Instance inst = Instance.builder("test-agent").id(UUID.randomUUID())
                .metadata(mutable).build();
        mutable.put("extra", "value");
        assertThat(inst.metadata()).doesNotContainKey("extra");
    }

    @Test
    void instanceRecord_nullMetadataRemains() {
        Instance inst = Instance.builder("test-agent").id(UUID.randomUUID()).build();
        assertThat(inst.metadata()).isNull();
    }

    @Test
    void instanceEntity_roundTrip() {
        Instance inst = Instance.builder("mesh-agent").id(UUID.randomUUID())
                .metadata(Map.of("family", "casehub", "slot", "174")).build();
        InstanceEntity entity = InstanceEntity.fromDomain(inst);
        assertThat(entity.metadata).isNotNull();
        Instance restored = entity.toDomain();
        assertThat(restored.metadata()).containsEntry("family", "casehub");
        assertThat(restored.metadata()).containsEntry("slot", "174");
    }

    @Test
    void instanceEntity_nullMetadata_roundTrip() {
        Instance inst = Instance.builder("bare-agent").id(UUID.randomUUID()).build();
        InstanceEntity entity = InstanceEntity.fromDomain(inst);
        assertThat(entity.metadata).isNull();
        Instance restored = entity.toDomain();
        assertThat(restored.metadata()).isNull();
    }

    @Test
    void backwardCompatConstructor_metadataNull() {
        Instance inst = new Instance(UUID.randomUUID(), "old-api", null, "online",
                null, null, false, null, null);
        assertThat(inst.metadata()).isNull();
    }

    @Test
    void toBuilder_preservesMetadata() {
        Instance inst = Instance.builder("builder-test").id(UUID.randomUUID())
                .metadata(Map.of("k", "v")).build();
        Instance copy = inst.toBuilder().build();
        assertThat(copy.metadata()).containsEntry("k", "v");
    }
}

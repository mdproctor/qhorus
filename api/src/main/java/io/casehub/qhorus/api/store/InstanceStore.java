package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.query.InstanceQuery;

public interface InstanceStore {
    Instance put(Instance instance);

    Optional<Instance> find(UUID id);

    Optional<Instance> findByInstanceId(String instanceId);

    List<Instance> scan(InstanceQuery query);

    void putCapabilities(UUID instanceId, List<String> tags);

    void deleteCapabilities(UUID instanceId);

    List<String> findCapabilities(UUID instanceId);

    void delete(UUID id);
}

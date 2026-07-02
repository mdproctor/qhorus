package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryInstanceStore implements InstanceStore {

    private final Map<UUID, Instance> store        = new LinkedHashMap<>();
    private final Map<UUID, List<String>> capabilities = new LinkedHashMap<>();

    @Override
    public Instance put(Instance instance) {
        Instant now = Instant.now();
        UUID id = instance.id();
        if (id == null) {
            id = UUID.randomUUID();
            instance = instance.toBuilder().id(id).build();
        }
        if (instance.registeredAt() == null) {
            instance = instance.toBuilder().registeredAt(now).build();
        }
        if (instance.lastSeen() == null) {
            instance = instance.toBuilder().lastSeen(now).build();
        }
        store.put(instance.id(), instance);
        return instance;
    }

    @Override
    public Optional<Instance> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Instance> findByInstanceId(String instanceId) {
        return store.values().stream()
                .filter(i -> instanceId.equals(i.instanceId()))
                .findFirst();
    }

    @Override
    public List<Instance> scan(InstanceQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .filter(i -> {
                    if (query.capability() == null) {
                        return true;
                    }
                    List<String> caps = capabilities.getOrDefault(i.id(), List.of());
                    return caps.contains(query.capability());
                })
                .toList();
    }

    @Override
    public void putCapabilities(UUID instanceId, List<String> tags) {
        capabilities.put(instanceId, new ArrayList<>(tags));
    }

    @Override
    public void deleteCapabilities(UUID instanceId) {
        capabilities.remove(instanceId);
    }

    @Override
    public List<String> findCapabilities(UUID instanceId) {
        return List.copyOf(capabilities.getOrDefault(instanceId, List.of()));
    }

    @Override
    public void delete(UUID id) {
        capabilities.remove(id);
        store.remove(id);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
        capabilities.clear();
    }
}

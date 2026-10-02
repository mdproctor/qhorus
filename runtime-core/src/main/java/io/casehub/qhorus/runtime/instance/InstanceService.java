package io.casehub.qhorus.runtime.instance;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.instance.InstanceManager;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InstanceService implements InstanceManager {

    private final InstanceStore instanceStore;
    private final jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceRegisteredEvent> registeredEvent;
    private final jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceDeregisteredEvent> deregisteredEvent;


    public InstanceService(InstanceStore instanceStore,
                           jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceRegisteredEvent> registeredEvent,
                           jakarta.enterprise.event.Event<io.casehub.qhorus.api.instance.InstanceDeregisteredEvent> deregisteredEvent) {
        this.instanceStore     = instanceStore;
        this.registeredEvent   = registeredEvent;
        this.deregisteredEvent = deregisteredEvent;
    }

    public InstanceService(InstanceStore instanceStore) {
        this(instanceStore, null, null);
    }


    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags) {
        return register(instanceId, description, capabilityTags, null, false);
    }

    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
                             String claudonySessionId) {
        return register(instanceId, description, capabilityTags, claudonySessionId, false);
    }

    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
                             String claudonySessionId, boolean readOnly) {
        Instance existing = instanceStore.findByInstanceId(instanceId).orElse(null);

        List<String> previousCaps = existing != null
                                    ? instanceStore.findCapabilities(existing.id())
                                    : List.of();

        Instance.Builder b;
        if (existing == null) {
            b = Instance.builder(instanceId);
        } else {
            b = existing.toBuilder();
        }
        Instance instance = b.description(description)
                             .status("online")
                             .lastSeen(Instant.now())
                             .claudonySessionId(claudonySessionId)
                             .readOnly(readOnly)
                             .build();
        Instance saved = instanceStore.put(instance);

        instanceStore.putCapabilities(saved.id(), capabilityTags);

        if (registeredEvent != null) {
            registeredEvent.fireAsync(new io.casehub.qhorus.api.instance.InstanceRegisteredEvent(
                    instanceId, previousCaps, capabilityTags));
        }

        return saved;
    }

    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
                             String claudonySessionId, boolean readOnly,
                             Map<String, String> metadata) {
        Instance existing = instanceStore.findByInstanceId(instanceId).orElse(null);

        List<String> previousCaps = existing != null
                                    ? instanceStore.findCapabilities(existing.id())
                                    : List.of();

        Instance.Builder b;
        if (existing == null) {
            b = Instance.builder(instanceId);
        } else {
            b = existing.toBuilder();
        }
        Instance instance = b.description(description)
                             .status("online")
                             .lastSeen(Instant.now())
                             .claudonySessionId(claudonySessionId)
                             .readOnly(readOnly)
                             .metadata(metadata)
                             .build();
        Instance saved = instanceStore.put(instance);

        instanceStore.putCapabilities(saved.id(), capabilityTags);

        if (registeredEvent != null) {
            registeredEvent.fireAsync(new io.casehub.qhorus.api.instance.InstanceRegisteredEvent(
                    instanceId, previousCaps, capabilityTags));
        }

        return saved;
    }


    @Transactional
    public void heartbeat(String instanceId) {
        instanceStore.findByInstanceId(instanceId).ifPresent(instance -> {
            instanceStore.put(instance.toBuilder()
                    .lastSeen(Instant.now())
                    .status("online")
                    .build());
        });
    }

    public Optional<Instance> findByInstanceId(String instanceId) {
        return instanceStore.findByInstanceId(instanceId);
    }

    public List<Instance> findByCapability(String tag) {
        return instanceStore.scan(InstanceQuery.byCapability(tag));
    }

    public List<String> findCapabilityTagsForInstance(String instanceId) {
        return instanceStore.findByInstanceId(instanceId)
                .map(i -> instanceStore.findCapabilities(i.id()))
                .orElse(List.of());
    }

    public List<Instance> listAll() {
        return instanceStore.scan(InstanceQuery.all());
    }

    @Transactional
    public void deregister(String instanceId) {
        instanceStore.findByInstanceId(instanceId)
                     .ifPresent(inst -> {
                         List<String> caps = instanceStore.findCapabilities(inst.id());
                         instanceStore.delete(inst.id());
                         if (deregisteredEvent != null) {
                             deregisteredEvent.fireAsync(new io.casehub.qhorus.api.instance.InstanceDeregisteredEvent(
                                     instanceId, caps));
                         }
                     });
    }

    @Transactional
    public void markStaleOlderThan(int thresholdSeconds) {
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        instanceStore.scan(InstanceQuery.all()).stream()
                .filter(i -> "online".equals(i.status()))
                .filter(i -> i.lastSeen() != null && i.lastSeen().isBefore(cutoff))
                .forEach(i -> instanceStore.put(i.toBuilder().status("stale").build()));
    }

    @Transactional
    public void markOffline(String instanceId) {
        instanceStore.findByInstanceId(instanceId)
                .ifPresent(i -> instanceStore.put(i.toBuilder().status("offline").build()));
    }

    @Override
    public Instance register(String instanceId, String description,
                             List<String> capabilities, boolean readOnly) {
        return register(instanceId, description, capabilities, null, readOnly);
    }

    @Override
    public List<InstanceInfo> listInfo() {
        return toInfoList(listAll());
    }

    @Override
    public List<InstanceInfo> findInfoByCapability(String capability) {
        return toInfoList(findByCapability(capability));
    }

    @Override
    public InstanceInfo findInfo(String instanceId) {
        Instance inst = findByInstanceId(instanceId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Instance not found: " + instanceId));
        return toInfo(inst);
    }

    private List<InstanceInfo> toInfoList(List<Instance> instances) {
        return instances.stream().map(this::toInfo).toList();
    }

    private InstanceInfo toInfo(Instance i) {
        List<String> caps = instanceStore.findCapabilities(i.id());
        return new InstanceInfo(
                i.instanceId(),
                i.description(),
                i.status(),
                caps,
                i.lastSeen() != null ? i.lastSeen().toString() : null,
                i.readOnly());
    }

}

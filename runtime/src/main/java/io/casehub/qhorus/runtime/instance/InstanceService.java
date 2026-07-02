package io.casehub.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;

@ApplicationScoped
public class InstanceService {

    @Inject
    InstanceStore instanceStore;

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
                .ifPresent(inst -> instanceStore.delete(inst.id()));
    }

    @Transactional
    public void markStaleOlderThan(int thresholdSeconds) {
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        InstanceEntity.update("status = 'stale' WHERE lastSeen < ?1 AND status = 'online'", cutoff);
    }
}

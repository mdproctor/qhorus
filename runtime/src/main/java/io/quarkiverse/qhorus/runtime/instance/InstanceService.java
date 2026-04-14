package io.quarkiverse.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class InstanceService {

    /**
     * Register or update an instance. Creates if not found; updates description,
     * status and lastSeen if already present. Replaces capability tags on upsert.
     */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags) {
        Instance instance = Instance.<Instance> find("instanceId", instanceId)
                .firstResult();

        if (instance == null) {
            instance = new Instance();
            instance.instanceId = instanceId;
        }

        instance.description = description;
        instance.status = "online";
        instance.lastSeen = Instant.now();
        instance.persist();

        // Replace capability tags — delete existing, insert new
        Capability.delete("instanceId", instance.id);
        for (String tag : capabilityTags) {
            Capability cap = new Capability();
            cap.instanceId = instance.id;
            cap.tag = tag;
            cap.persist();
        }

        return instance;
    }

    @Transactional
    public void heartbeat(String instanceId) {
        Instance instance = Instance.<Instance> find("instanceId", instanceId).firstResult();
        if (instance != null) {
            instance.lastSeen = Instant.now();
            instance.status = "online";
        }
    }

    public Optional<Instance> findByInstanceId(String instanceId) {
        return Instance.find("instanceId", instanceId).firstResultOptional();
    }

    public List<Instance> findByCapability(String tag) {
        return Instance.find(
                "id IN (SELECT c.instanceId FROM Capability c WHERE c.tag = ?1)", tag)
                .list();
    }

    public List<Instance> listAll() {
        return Instance.listAll();
    }

    @Transactional
    public void markStaleOlderThan(int thresholdSeconds) {
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        Instance.update("status = 'stale' WHERE lastSeen < ?1 AND status = 'online'", cutoff);
    }
}

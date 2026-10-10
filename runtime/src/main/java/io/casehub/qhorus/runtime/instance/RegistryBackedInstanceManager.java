package io.casehub.qhorus.runtime.instance;

import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.instance.InstanceDeregisteredEvent;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.instance.InstanceManager;
import io.casehub.qhorus.api.instance.InstanceRegisteredEvent;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "casehub.qhorus.instance.registry-backed",
                 stringValue = "true", enableIfMissing = false)
public class RegistryBackedInstanceManager implements InstanceManager {

    static final String   TYPE        = "agent-instance";
    static final String   NAMESPACE   = "default";
    static final String   TENANCY_ID  = "default";
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final RegistryService                  registry;
    private final Event<InstanceRegisteredEvent>   registeredEvent;
    private final Event<InstanceDeregisteredEvent> deregisteredEvent;

    @jakarta.inject.Inject
    RegistryBackedInstanceManager(RegistryService registry,
                                  Event<InstanceRegisteredEvent> registeredEvent,
                                  Event<InstanceDeregisteredEvent> deregisteredEvent) {
        this.registry          = registry;
        this.registeredEvent   = registeredEvent;
        this.deregisteredEvent = deregisteredEvent;
    }

    RegistryBackedInstanceManager(RegistryService registry) {
        this(registry, null, null);
    }

    @Override
    public Instance register(String instanceId, String description,
                             List<String> capabilities, boolean readOnly) {
        List<String> previousCaps = registry.resolve(instanceId)
                                            .filter(e -> TYPE.equals(e.type()))
                                            .map(this::extractCapabilities)
                                            .orElse(List.of());

        var now      = Instant.now();
        var metadata = new HashMap<String, String>();
        metadata.put("description", description);
        metadata.put("capabilities", String.join(",", capabilities));
        metadata.put("readOnly", String.valueOf(readOnly));

        var entry = new RegistryEntry(
                instanceId, TYPE, NAMESPACE, TENANCY_ID,
                metadata, now, now, DEFAULT_TTL, HealthStatus.HEALTHY
        );
        registry.register(entry);

        if (registeredEvent != null) {
            registeredEvent.fireAsync(new InstanceRegisteredEvent(
                    instanceId, previousCaps, capabilities));
        }

        return Instance.builder(instanceId)
                       .description(description)
                       .status("online")
                       .readOnly(readOnly)
                       .lastSeen(now)
                       .registeredAt(now)
                       .build();
    }

    @Override
    public List<InstanceInfo> listInfo() {
        return registry.discover(new RegistryQuery(TENANCY_ID, TYPE, null))
                       .stream()
                       .map(this::toInfo)
                       .toList();
    }

    @Override
    public List<InstanceInfo> findInfoByCapability(String capability) {
        return listInfo().stream()
                         .filter(i -> i.capabilities().contains(capability))
                         .toList();
    }

    @Override
    public InstanceInfo findInfo(String instanceId) {
        return registry.resolve(instanceId)
                       .filter(e -> TYPE.equals(e.type()))
                       .map(this::toInfo)
                       .orElseThrow(() -> new IllegalArgumentException(
                               "Instance not found: " + instanceId));
    }

    @Override
    public void deregister(String instanceId) {
        var existing = registry.resolve(instanceId)
                               .filter(e -> TYPE.equals(e.type()));
        List<String> caps = existing.map(this::extractCapabilities)
                                    .orElse(List.of());

        registry.deregister(instanceId);

        if (deregisteredEvent != null && existing.isPresent()) {
            deregisteredEvent.fireAsync(new InstanceDeregisteredEvent(
                    instanceId, caps));
        }
    }

    private List<String> extractCapabilities(RegistryEntry entry) {
        var caps = entry.metadata().getOrDefault("capabilities", "");
        return caps.isEmpty() ? List.of() : List.of(caps.split(","));
    }

    private InstanceInfo toInfo(RegistryEntry entry) {
        return new InstanceInfo(
                entry.id(),
                entry.metadata().getOrDefault("description", ""),
                mapHealthToStatus(entry.health()),
                extractCapabilities(entry),
                entry.lastHeartbeat() != null ? entry.lastHeartbeat().toString() : null,
                Boolean.parseBoolean(entry.metadata().getOrDefault("readOnly", "false"))
        );
    }

    private String mapHealthToStatus(HealthStatus health) {
        return switch (health) {
            case HEALTHY -> "online";
            case DEGRADED -> "stale";
            case DOWN -> "offline";
        };
    }
}


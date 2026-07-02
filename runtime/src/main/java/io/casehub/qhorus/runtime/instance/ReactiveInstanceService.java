package io.casehub.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.ReactiveInstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveInstanceService {

    @Inject
    ReactiveInstanceStore instanceStore;

    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags) {
        return register(instanceId, description, capabilityTags, null, false);
    }

    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags,
                                  String claudonySessionId) {
        return register(instanceId, description, capabilityTags, claudonySessionId, false);
    }

    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags,
                                  String claudonySessionId, boolean readOnly) {
        return Panache.withTransaction("qhorus", () -> instanceStore.findByInstanceId(instanceId).flatMap(opt -> {
            Instance.Builder b;
            if (opt.isEmpty()) {
                b = Instance.builder(instanceId);
            } else {
                b = opt.get().toBuilder();
            }
            Instance instance = b.description(description)
                    .status("online")
                    .lastSeen(Instant.now())
                    .claudonySessionId(claudonySessionId)
                    .readOnly(readOnly)
                    .build();
            return instanceStore.put(instance)
                    .flatMap(saved -> instanceStore.putCapabilities(saved.id(), capabilityTags)
                            .map(ignored -> saved));
        }));
    }

    public Uni<Void> heartbeat(String instanceId) {
        return Panache.withTransaction("qhorus", () -> instanceStore.findByInstanceId(instanceId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Uni.createFrom().voidItem();
                    Instance updated = opt.get().toBuilder().lastSeen(Instant.now()).status("online").build();
                    return instanceStore.put(updated).replaceWithVoid();
                }));
    }

    public Uni<Optional<Instance>> findByInstanceId(String instanceId) {
        return instanceStore.findByInstanceId(instanceId);
    }

    public Uni<List<Instance>> findByCapability(String tag) {
        return instanceStore.scan(InstanceQuery.byCapability(tag));
    }

    public Uni<List<String>> findCapabilityTagsForInstance(String instanceId) {
        return instanceStore.findByInstanceId(instanceId)
                .flatMap(opt -> opt.isPresent()
                        ? instanceStore.findCapabilities(opt.get().id())
                        : Uni.createFrom().item(List.of()));
    }

    public Uni<List<Instance>> listAll() {
        return instanceStore.scan(InstanceQuery.all());
    }

    public Uni<Void> deregister(String instanceId) {
        return Panache.withTransaction("qhorus", () ->
                instanceStore.findByInstanceId(instanceId)
                        .flatMap(opt -> opt.isPresent()
                                ? instanceStore.delete(opt.get().id())
                                : Uni.createFrom().voidItem()));
    }
}

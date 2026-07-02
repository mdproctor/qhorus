package io.casehub.qhorus.runtime.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.ReactiveDataStore;
import io.casehub.qhorus.api.store.query.DataQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveDataService {

    @Inject
    ReactiveDataStore dataStore;

    public Uni<SharedData> store(String key, String description, String createdBy,
                                 String content, boolean append, boolean lastChunk) {
        return Panache.withTransaction("qhorus", () -> dataStore.findByKey(key).flatMap(existing -> {
            String newContent;
            String effectiveCreatedBy;
            String effectiveDescription;
            UUID existingId = null;

            if (existing.isEmpty() || !append) {
                if (existing.isPresent()) {
                    existingId = existing.get().id();
                    effectiveCreatedBy = existing.get().createdBy();
                } else {
                    effectiveCreatedBy = createdBy;
                }
                effectiveDescription = description != null ? description : (existing.isPresent() ? existing.get().description() : null);
                newContent = content;
            } else {
                SharedData ex = existing.get();
                existingId = ex.id();
                effectiveCreatedBy = ex.createdBy();
                effectiveDescription = ex.description();
                newContent = (ex.content() != null ? ex.content() : "") + content;
            }

            SharedData.Builder b = SharedData.builder(key)
                    .content(newContent)
                    .createdBy(effectiveCreatedBy)
                    .complete(lastChunk)
                    .sizeBytes(newContent != null ? newContent.length() : 0);
            if (effectiveDescription != null) b.description(effectiveDescription);
            if (existingId != null) b.id(existingId);

            return dataStore.put(b.build());
        }));
    }

    public Uni<Optional<SharedData>> getByKey(String key) {
        return dataStore.findByKey(key);
    }

    public Uni<Optional<SharedData>> getByUuid(UUID id) {
        return dataStore.find(id);
    }

    public Uni<List<SharedData>> listAll() {
        return dataStore.scan(DataQuery.all());
    }

    public Uni<Void> claim(UUID artefactId, UUID instanceId) {
        return Panache.withTransaction("qhorus", () -> dataStore.hasClaim(artefactId, instanceId).flatMap(exists -> {
            if (exists) {
                return Uni.createFrom().voidItem();
            }
            return dataStore.putClaim(new ArtefactClaim(null, artefactId, instanceId, null)).replaceWithVoid();
        }));
    }

    public Uni<Void> release(UUID artefactId, UUID instanceId) {
        return Panache.withTransaction("qhorus", () -> dataStore.deleteClaim(artefactId, instanceId));
    }

    public Uni<Boolean> isGcEligible(UUID artefactId) {
        return dataStore.find(artefactId)
                .flatMap(opt -> {
                    if (opt.isEmpty() || !opt.get().complete()) {
                        return Uni.createFrom().item(false);
                    }
                    return dataStore.countClaims(artefactId).map(count -> count == 0);
                });
    }
}

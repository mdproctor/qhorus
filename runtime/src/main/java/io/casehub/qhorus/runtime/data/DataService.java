package io.casehub.qhorus.runtime.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.query.DataQuery;

@ApplicationScoped
public class DataService {

    @Inject
    DataStore dataStore;

    @Transactional
    public SharedData store(String key, String description, String createdBy,
                            String content, boolean append, boolean lastChunk) {
        SharedData existing = dataStore.findByKey(key).orElse(null);

        String newContent;
        String effectiveCreatedBy;
        String effectiveDescription;
        UUID existingId = null;

        if (existing == null || !append) {
            if (existing != null) {
                existingId = existing.id();
                effectiveCreatedBy = existing.createdBy();
            } else {
                effectiveCreatedBy = createdBy;
            }
            effectiveDescription = description != null ? description : (existing != null ? existing.description() : null);
            newContent = content;
        } else {
            existingId = existing.id();
            effectiveCreatedBy = existing.createdBy();
            effectiveDescription = existing.description();
            newContent = (existing.content() != null ? existing.content() : "") + content;
        }

        SharedData.Builder b = SharedData.builder(key)
                .content(newContent)
                .createdBy(effectiveCreatedBy)
                .complete(lastChunk)
                .sizeBytes(newContent != null ? newContent.length() : 0);
        if (effectiveDescription != null) b.description(effectiveDescription);
        if (existingId != null) b.id(existingId);

        return dataStore.put(b.build());
    }

    public Optional<SharedData> getByKey(String key) {
        return dataStore.findByKey(key);
    }

    public Optional<SharedData> getByUuid(UUID id) {
        return dataStore.find(id);
    }

    public List<SharedData> listAll() {
        return dataStore.scan(DataQuery.all());
    }

    @Transactional
    public void claim(UUID artefactId, UUID instanceId) {
        if (dataStore.countClaims(artefactId) == 0 ||
                !hasClaim(artefactId, instanceId)) {
            dataStore.putClaim(new ArtefactClaim(null, artefactId, instanceId, null));
        }
    }

    private boolean hasClaim(UUID artefactId, UUID instanceId) {
        return ArtefactClaimEntity.count("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId) > 0;
    }

    @Transactional
    public void release(UUID artefactId, UUID instanceId) {
        dataStore.deleteClaim(artefactId, instanceId);
    }

    public boolean isGcEligible(UUID artefactId) {
        return dataStore.find(artefactId)
                .filter(SharedData::complete)
                .map(d -> dataStore.countClaims(artefactId) == 0)
                .orElse(false);
    }
}

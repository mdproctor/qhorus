package io.quarkiverse.qhorus.runtime.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DataService {

    /**
     * Store or update a shared data artefact.
     *
     * @param key human-readable key (unique)
     * @param description optional description (ignored on append chunks)
     * @param createdBy owner instance ID
     * @param content content to store or append
     * @param append if true, append to existing content; if false, create/overwrite
     * @param lastChunk if true, mark the artefact as complete
     */
    @Transactional
    public SharedData store(String key, String description, String createdBy,
            String content, boolean append, boolean lastChunk) {
        SharedData data = SharedData.<SharedData> find("key", key).firstResult();

        if (data == null || !append) {
            if (data == null) {
                data = new SharedData();
                data.key = key;
                data.createdBy = createdBy;
            }
            if (description != null) {
                data.description = description;
            }
            data.content = content;
        } else {
            // append chunk to existing content
            data.content = (data.content != null ? data.content : "") + content;
        }

        data.complete = lastChunk;
        data.sizeBytes = data.content != null ? data.content.length() : 0;
        data.persist();
        return data;
    }

    public Optional<SharedData> getByKey(String key) {
        return SharedData.find("key", key).firstResultOptional();
    }

    public Optional<SharedData> getByUuid(UUID id) {
        return SharedData.findByIdOptional(id);
    }

    public List<SharedData> listAll() {
        return SharedData.listAll();
    }

    @Transactional
    public void claim(UUID artefactId, UUID instanceId) {
        ArtefactClaim claim = new ArtefactClaim();
        claim.artefactId = artefactId;
        claim.instanceId = instanceId;
        claim.persist();
    }

    @Transactional
    public void release(UUID artefactId, UUID instanceId) {
        ArtefactClaim.delete("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId);
    }

    /**
     * An artefact is GC-eligible when it is complete AND has no active claims.
     */
    public boolean isGcEligible(UUID artefactId) {
        SharedData data = SharedData.findById(artefactId);
        if (data == null || !data.complete) {
            return false;
        }
        long claimCount = ArtefactClaim.count("artefactId", artefactId);
        return claimCount == 0;
    }
}

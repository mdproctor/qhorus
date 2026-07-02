package io.casehub.qhorus.api.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.query.DataQuery;

public interface DataStore {
    SharedData put(SharedData data);

    Optional<SharedData> find(UUID id);

    List<SharedData> findByIds(Collection<UUID> ids);

    Optional<SharedData> findByKey(String key);

    List<SharedData> scan(DataQuery query);

    ArtefactClaim putClaim(ArtefactClaim claim);

    void deleteClaim(UUID artefactId, UUID instanceId);

    int countClaims(UUID artefactId);

    void delete(UUID id);
}

package io.quarkiverse.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveDataStore {
    Uni<SharedData> put(SharedData data);

    Uni<Optional<SharedData>> find(UUID id);

    Uni<Optional<SharedData>> findByKey(String key);

    Uni<List<SharedData>> scan(DataQuery query);

    Uni<ArtefactClaim> putClaim(ArtefactClaim claim);

    Uni<Void> deleteClaim(UUID artefactId, UUID instanceId);

    Uni<Integer> countClaims(UUID artefactId);

    Uni<Void> delete(UUID id);
}

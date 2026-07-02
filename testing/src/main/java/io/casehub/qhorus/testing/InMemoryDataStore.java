package io.casehub.qhorus.testing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.query.DataQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryDataStore implements DataStore {

    private final Map<UUID, SharedData> store  = new LinkedHashMap<>();
    private final List<ArtefactClaim>   claims = new ArrayList<>();

    @Override
    public SharedData put(SharedData data) {
        if (data.id() == null) {
            data = new SharedData(UUID.randomUUID(), data.key(), data.content(), data.createdBy(),
                    data.description(), data.complete(), data.sizeBytes(), data.createdAt(), data.updatedAt());
        }
        store.put(data.id(), data);
        return data;
    }

    @Override
    public Optional<SharedData> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<SharedData> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(store::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<SharedData> findByKey(String key) {
        return store.values().stream()
                .filter(d -> key.equals(d.key()))
                .findFirst();
    }

    @Override
    public List<SharedData> scan(DataQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public ArtefactClaim putClaim(ArtefactClaim claim) {
        UUID id = claim.id();
        if (id == null) {
            id = UUID.randomUUID();
            claim = new ArtefactClaim(id, claim.artefactId(), claim.instanceId(), claim.claimedAt());
        }
        claims.add(claim);
        return claim;
    }

    @Override
    public void deleteClaim(UUID artefactId, UUID instanceId) {
        claims.removeIf(c -> artefactId.equals(c.artefactId()) && instanceId.equals(c.instanceId()));
    }

    @Override
    public int countClaims(UUID artefactId) {
        return (int) claims.stream()
                .filter(c -> artefactId.equals(c.artefactId()))
                .count();
    }

    public boolean hasClaim(UUID artefactId, UUID instanceId) {
        return claims.stream()
                .anyMatch(c -> artefactId.equals(c.artefactId()) && instanceId.equals(c.instanceId()));
    }

    @Override
    public void delete(UUID id) {
        claims.removeIf(c -> id.equals(c.artefactId()));
        store.remove(id);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
        claims.clear();
    }
}

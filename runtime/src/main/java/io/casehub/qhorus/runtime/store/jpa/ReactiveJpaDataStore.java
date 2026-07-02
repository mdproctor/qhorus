package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.runtime.data.ArtefactClaimEntity;
import io.casehub.qhorus.runtime.data.SharedDataEntity;
import io.casehub.qhorus.api.store.ReactiveDataStore;
import io.casehub.qhorus.api.store.query.DataQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveJpaDataStore implements ReactiveDataStore {

    @Inject
    SharedDataReactivePanacheRepo dataRepo;

    @Inject
    ArtefactClaimReactivePanacheRepo claimRepo;

    @Override
    @WithTransaction
    public Uni<SharedData> put(SharedData data) {
        SharedDataEntity entity = SharedDataEntity.fromDomain(data);
        return dataRepo.persist(entity).map(SharedDataEntity::toDomain);
    }

    @Override
    public Uni<Optional<SharedData>> find(UUID id) {
        return dataRepo.findById(id)
                .map(e -> Optional.ofNullable(e).map(SharedDataEntity::toDomain));
    }

    @Override
    public Uni<List<SharedData>> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Uni.createFrom().item(List.of());
        return dataRepo.<SharedDataEntity>list("id IN ?1", new ArrayList<>(ids))
                .map(list -> list.stream().map(SharedDataEntity::toDomain).toList());
    }

    @Override
    public Uni<Optional<SharedData>> findByKey(String key) {
        return dataRepo.find("key", key).firstResult()
                .map(e -> Optional.ofNullable(e).map(SharedDataEntity::toDomain));
    }

    @Override
    public Uni<List<SharedData>> scan(DataQuery q) {
        StringBuilder jpql = new StringBuilder("FROM SharedData WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.createdBy() != null) {
            jpql.append(" AND createdBy = ?").append(idx++);
            params.add(q.createdBy());
        }
        if (q.complete() != null) {
            jpql.append(" AND complete = ?").append(idx++);
            params.add(q.complete());
        }

        return dataRepo.<SharedDataEntity>list(jpql.toString(), params.toArray())
                .map(list -> list.stream().map(SharedDataEntity::toDomain).toList());
    }

    @Override
    @WithTransaction
    public Uni<ArtefactClaim> putClaim(ArtefactClaim claim) {
        ArtefactClaimEntity entity = ArtefactClaimEntity.fromDomain(claim);
        return claimRepo.persist(entity).map(ArtefactClaimEntity::toDomain);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteClaim(UUID artefactId, UUID instanceId) {
        return claimRepo.delete("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId)
                .replaceWithVoid();
    }

    @Override
    public Uni<Integer> countClaims(UUID artefactId) {
        return claimRepo.count("artefactId", artefactId).map(Long::intValue);
    }

    @Override
    public Uni<Boolean> hasClaim(UUID artefactId, UUID instanceId) {
        return claimRepo.count("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId)
                .map(c -> c > 0);
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return claimRepo.delete("artefactId", id)
                .flatMap(ignored -> dataRepo.deleteById(id))
                .replaceWithVoid();
    }
}

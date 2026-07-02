package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link CommitmentEntity}.
 *
 * <p>
 * Active when {@code casehub.qhorus.reactive.enabled=true}; excluded from CDI
 * otherwise. Kept package-private and injected into {@link ReactiveJpaCommitmentStore}.
 *
 * <p>
 * Refs #193.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
class CommitmentReactivePanacheRepo implements PanacheRepositoryBase<CommitmentEntity, UUID> {
}

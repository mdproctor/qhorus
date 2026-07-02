package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
class CommitmentPanacheRepo implements PanacheRepositoryBase<CommitmentEntity, UUID> {
}

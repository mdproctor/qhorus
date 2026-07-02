package io.casehub.qhorus.runtime.store.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.runtime.gateway.DeliveryCursorEntity;
import java.util.UUID;

@ApplicationScoped
public class DeliveryCursorPanacheRepo implements PanacheRepositoryBase<DeliveryCursorEntity, UUID> {}

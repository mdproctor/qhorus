package io.casehub.qhorus.runtime.store.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.runtime.gateway.DeliveryCursor;
import java.util.UUID;

@ApplicationScoped
public class DeliveryCursorPanacheRepo implements PanacheRepositoryBase<DeliveryCursor, UUID> {}

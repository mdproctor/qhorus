package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link ChannelEntity}.
 *
 * <p>
 * Active when {@code casehub.qhorus.reactive.enabled=true}; excluded from CDI by
 * {@code QhorusProcessor} otherwise. This prevents Hibernate Reactive from booting in
 * applications that only use the blocking {@link JpaChannelStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaChannelStore}.
 *
 * <p>
 * Refs #74.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
class ChannelReactivePanacheRepo implements PanacheRepositoryBase<ChannelEntity, UUID> {
}

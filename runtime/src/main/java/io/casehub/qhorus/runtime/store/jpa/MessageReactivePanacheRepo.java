package io.casehub.qhorus.runtime.store.jpa;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.runtime.message.MessageEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link MessageEntity}.
 *
 * <p>
 * Active when {@code casehub.qhorus.reactive.enabled=true}; excluded from CDI by
 * {@code QhorusProcessor} otherwise. This prevents Hibernate Reactive from booting in
 * applications that only use the blocking {@link JpaMessageStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaMessageStore}.
 *
 * <p>
 * Note: {@link MessageEntity} uses {@code Long} as its primary key.
 *
 * <p>
 * Refs #74.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
class MessageReactivePanacheRepo implements PanacheRepositoryBase<MessageEntity, Long> {
}

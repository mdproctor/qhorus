package io.quarkiverse.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link PendingReply}.
 *
 * <p>
 * Marked {@code @Alternative} so it is not active by default — consumers must select it
 * explicitly via {@code quarkus.arc.selected-alternatives} when they configure a reactive
 * datasource. This prevents Hibernate Reactive from booting in applications that only use
 * the blocking {@link JpaPendingReplyStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaPendingReplyStore}.
 *
 * <p>
 * Refs #86.
 */
@Alternative
@ApplicationScoped
class PendingReplyReactivePanacheRepo implements PanacheRepositoryBase<PendingReply, UUID> {
}

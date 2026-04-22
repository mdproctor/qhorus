package io.quarkiverse.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.ReactivePendingReplyStore;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveJpaPendingReplyStore implements ReactivePendingReplyStore {

    @Inject
    PendingReplyReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<PendingReply> save(PendingReply pr) {
        return repo.persist(pr);
    }

    @Override
    public Uni<Optional<PendingReply>> findByCorrelationId(String correlationId) {
        return repo.find("correlationId", correlationId).firstResult().map(Optional::ofNullable);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteByCorrelationId(String correlationId) {
        return repo.delete("correlationId", correlationId).replaceWithVoid();
    }

    @Override
    public Uni<Boolean> existsByCorrelationId(String correlationId) {
        return repo.count("correlationId", correlationId).map(count -> count > 0);
    }

    @Override
    public Uni<List<PendingReply>> findExpiredBefore(Instant cutoff) {
        return repo.list("expiresAt < ?1", cutoff);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteExpiredBefore(Instant cutoff) {
        return repo.delete("expiresAt < ?1", cutoff).replaceWithVoid();
    }
}

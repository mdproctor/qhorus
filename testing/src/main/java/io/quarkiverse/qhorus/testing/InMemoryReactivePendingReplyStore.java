package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.ReactivePendingReplyStore;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactivePendingReplyStore implements ReactivePendingReplyStore {

    private final InMemoryPendingReplyStore delegate = new InMemoryPendingReplyStore();

    @Override
    public Uni<PendingReply> save(PendingReply pr) {
        return Uni.createFrom().item(() -> delegate.save(pr));
    }

    @Override
    public Uni<Optional<PendingReply>> findByCorrelationId(String correlationId) {
        return Uni.createFrom().item(() -> delegate.findByCorrelationId(correlationId));
    }

    @Override
    public Uni<Void> deleteByCorrelationId(String correlationId) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.deleteByCorrelationId(correlationId));
    }

    @Override
    public Uni<Boolean> existsByCorrelationId(String correlationId) {
        return Uni.createFrom().item(() -> delegate.existsByCorrelationId(correlationId));
    }

    @Override
    public Uni<List<PendingReply>> findExpiredBefore(Instant cutoff) {
        return Uni.createFrom().item(() -> delegate.findExpiredBefore(cutoff));
    }

    @Override
    public Uni<Void> deleteExpiredBefore(Instant cutoff) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.deleteExpiredBefore(cutoff));
    }

    public void clear() {
        delegate.clear();
    }
}

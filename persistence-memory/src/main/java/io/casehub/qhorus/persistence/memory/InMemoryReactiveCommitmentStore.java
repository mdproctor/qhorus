package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveCommitmentStore implements ReactiveCommitmentStore {

    @Inject
    InMemoryCommitmentStore delegate;

    @Override
    public Uni<Commitment> save(Commitment c) {
        return Uni.createFrom().item(delegate.save(c));
    }

    @Override
    public Uni<Optional<Commitment>> findById(UUID id) {
        return Uni.createFrom().item(delegate.findById(id));
    }

    @Override
    public Uni<Optional<Commitment>> findByCorrelationId(String correlationId) {
        return Uni.createFrom().item(delegate.findByCorrelationId(correlationId));
    }

    @Override
    public Uni<List<Commitment>> findAllByCorrelationId(String correlationId) {
        return Uni.createFrom().item(delegate.findAllByCorrelationId(correlationId));
    }


    @Override
    public Uni<List<Commitment>> findByIds(java.util.Collection<java.util.UUID> ids) {
        return Uni.createFrom().item(delegate.findByIds(ids));
    }


    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor, UUID channelId) {
        return Uni.createFrom().item(delegate.findOpenByObligor(obligor, channelId));
    }

    @Override
    public Uni<List<Commitment>> findOpenByObligor(String obligor) {
        return Uni.createFrom().item(() -> delegate.findOpenByObligor(obligor));
    }

    @Override
    public Uni<List<Commitment>> findOpenByRequester(String requester, UUID channelId) {
        return Uni.createFrom().item(delegate.findOpenByRequester(requester, channelId));
    }

    @Override
    public Uni<List<Commitment>> findByState(CommitmentState state, UUID channelId) {
        return Uni.createFrom().item(delegate.findByState(state, channelId));
    }

    @Override
    public Uni<List<Commitment>> findByChannel(UUID channelId) {
        return Uni.createFrom().item(delegate.findByChannel(channelId));
    }

    @Override
    public Uni<List<Commitment>> findOpenByChannelIdAsync(UUID channelId) {
        return Uni.createFrom().item(delegate.findOpenByChannelId(channelId));
    }


    @Override
    public Uni<List<Commitment>> findExpiredBefore(Instant cutoff) {
        return Uni.createFrom().item(delegate.findExpiredBefore(cutoff));
    }

    @Override
    public Uni<List<Commitment>> findAllOpen() {
        return Uni.createFrom().item(delegate.findAllOpen());
    }

    @Override
    public Uni<Void> deleteById(UUID id) {
        delegate.deleteById(id);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Long> deleteExpiredBefore(Instant cutoff) {
        return Uni.createFrom().item(delegate.deleteExpiredBefore(cutoff));
    }
}

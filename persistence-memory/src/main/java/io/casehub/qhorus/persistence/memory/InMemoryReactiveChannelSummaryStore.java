package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.ReactiveChannelSummaryStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Optional;
import java.util.UUID;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveChannelSummaryStore implements ReactiveChannelSummaryStore {

    private final InMemoryChannelSummaryStore delegate = new InMemoryChannelSummaryStore();

    @Override
    public Uni<ChannelSummary> save(ChannelSummary summary) {
        return Uni.createFrom().item(delegate.save(summary));
    }

    @Override
    public Uni<Optional<ChannelSummary>> findByChannelId(UUID channelId) {
        return Uni.createFrom().item(delegate.findByChannelId(channelId));
    }

    @Override
    public Uni<Void> deleteByChannelId(UUID channelId) {
        delegate.deleteByChannelId(channelId);
        return Uni.createFrom().voidItem();
    }
}

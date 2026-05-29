package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveChannelStore implements ReactiveChannelStore {

    /** Injected by CDI in @QuarkusTest; may be set directly in plain unit tests. */
    @Inject
    InMemoryChannelStore blocking = new InMemoryChannelStore();

    @Override
    public Uni<Channel> put(Channel channel) {
        return Uni.createFrom().item(() -> blocking.put(channel));
    }

    @Override
    public Uni<Optional<Channel>> find(UUID id) {
        return Uni.createFrom().item(() -> blocking.find(id));
    }

    @Override
    public Uni<Optional<Channel>> findByName(String name) {
        return Uni.createFrom().item(() -> blocking.findByName(name));
    }

    @Override
    public Uni<List<Channel>> scan(ChannelQuery query) {
        return Uni.createFrom().item(() -> blocking.scan(query));
    }

    @Override
    public Uni<Void> delete(UUID id) {
        return Uni.createFrom().voidItem().invoke(() -> blocking.delete(id));
    }

    @Override
    public Uni<Void> updateLastActivity(UUID channelId) {
        return Uni.createFrom().voidItem().invoke(() ->
            blocking.find(channelId).ifPresent(ch -> ch.lastActivityAt = Instant.now()));
    }

    public void clear() {
        blocking.clear();
    }
}

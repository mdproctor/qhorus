package io.casehub.qhorus.persistence.memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
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
    public Uni<Void> updateLastActivity(UUID channelId, String tenancyId) {
        // No-op — delegates to InMemoryChannelStore which is a no-op for the same reason.
        // See InMemoryChannelStore.updateLastActivity() for the full explanation.
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<Channel>> findByIds(Collection<UUID> ids) {
        return Uni.createFrom().item(() -> blocking.findByIds(ids));
    }

    public void clear() {
        blocking.clear();
    }
}

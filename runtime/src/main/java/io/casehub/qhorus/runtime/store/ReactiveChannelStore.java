package io.casehub.qhorus.runtime.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveChannelStore {
    Uni<Channel> put(Channel channel);

    Uni<Optional<Channel>> find(UUID id);

    Uni<Optional<Channel>> findByName(String name);

    Uni<List<Channel>> scan(ChannelQuery query);

    Uni<Void> delete(UUID id);

    /**
     * Sets {@code lastActivityAt = now()} for the given channel. The JPA implementation loads
     * the entity via {@code findById()} and relies on Hibernate Reactive's dirty checking to
     * flush the mutation; no raw UPDATE query is issued. This approach works within a reactive
     * session/transaction context where the named "qhorus" {@code SessionFactory} is available.
     *
     * <p>
     * Must be called within an active Hibernate Reactive session/transaction context.
     */
    Uni<Void> updateLastActivity(UUID channelId);

    /**
     * Batch lookup of channels by ID set (reactive).
     * Returns only channels that exist — missing IDs are silently omitted.
     * Empty collection input returns an empty list without querying the store.
     */
    Uni<List<Channel>> findByIds(Collection<UUID> ids);
}

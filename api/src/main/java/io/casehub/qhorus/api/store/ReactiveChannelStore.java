package io.casehub.qhorus.api.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveChannelStore {
    Uni<Channel> put(Channel channel);

    Uni<Optional<Channel>> find(UUID id);

    Uni<Optional<Channel>> findByName(String name);

    Uni<List<Channel>> scan(ChannelQuery query);

    Uni<Void> delete(UUID id);

    /**
     * Sets {@code lastActivityAt = now()} for the given channel, filtered by {@code tenancyId}
     * as defense-in-depth against cross-tenant mutation.
     *
     * <p>The {@code tenancyId} is passed explicitly by the caller so this method is safe to call
     * from contexts that have no request-scoped {@code CurrentPrincipal} (e.g. scheduler threads).
     *
     * <p>Must be called within an active Hibernate Reactive session/transaction context.
     */
    Uni<Void> updateLastActivity(UUID channelId, String tenancyId);

    /**
     * Batch lookup of channels by ID set (reactive).
     * Returns only channels that exist — missing IDs are silently omitted.
     * Empty collection input returns an empty list without querying the store.
     */
    Uni<List<Channel>> findByIds(Collection<UUID> ids);
}

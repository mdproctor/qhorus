package io.casehub.qhorus.runtime.store;

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
     * Issues a targeted UPDATE setting {@code lastActivityAt = now()} for {@code channelId}.
     * Must be called within an active Hibernate Reactive session/transaction context.
     * Does NOT load or re-attach the channel entity — avoids detached-entity issues when
     * the channel was loaded pre-transaction.
     */
    Uni<Void> updateLastActivity(UUID channelId);
}

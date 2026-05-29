package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

public interface ChannelStore {
    Channel put(Channel channel);

    Optional<Channel> find(UUID id);

    Optional<Channel> findByName(String name);

    List<Channel> scan(ChannelQuery query);

    void delete(UUID id);

    /**
     * Issues a targeted UPDATE setting {@code lastActivityAt = now()} for {@code channelId}.
     * Does NOT load or re-attach the channel entity — avoids detached-entity issues when
     * the channel was loaded pre-transaction.
     */
    void updateLastActivity(UUID channelId);
}

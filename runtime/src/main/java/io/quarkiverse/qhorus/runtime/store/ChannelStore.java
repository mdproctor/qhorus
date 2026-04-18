package io.quarkiverse.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.store.query.ChannelQuery;

public interface ChannelStore {
    Channel put(Channel channel);

    Optional<Channel> find(UUID id);

    Optional<Channel> findByName(String name);

    List<Channel> scan(ChannelQuery query);

    void delete(UUID id);
}

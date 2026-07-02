package io.casehub.qhorus.api.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.query.ChannelQuery;

public interface ChannelStore {
    Channel put(Channel channel);

    Optional<Channel> find(UUID id);

    Optional<Channel> findByName(String name);

    List<Channel> scan(ChannelQuery query);

    void delete(UUID id);

    void updateLastActivity(UUID channelId, String tenancyId);

    default List<Channel> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(this::find)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }
}

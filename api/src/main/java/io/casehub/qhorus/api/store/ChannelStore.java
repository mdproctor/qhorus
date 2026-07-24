package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.query.ChannelQuery;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelStore {
    Channel put(Channel channel);

    Optional<Channel> find(UUID id);

    Optional<Channel> findByName(String name);

    List<Channel> scan(ChannelQuery query);

    void delete(UUID id);

    void updateLastActivity(UUID channelId, String tenancyId);

    void updateTrackDelivery(UUID channelId, Boolean trackDelivery);


    default List<Channel> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(this::find)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    default boolean hasChannelsInSpace(UUID spaceId) {
        if (spaceId == null) {return false;}
        return !scan(io.casehub.qhorus.api.store.query.ChannelQuery.bySpaceId(spaceId)).isEmpty();
    }

}

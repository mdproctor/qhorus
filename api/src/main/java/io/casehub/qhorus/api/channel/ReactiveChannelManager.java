package io.casehub.qhorus.api.channel;

import io.casehub.qhorus.api.message.MessageType;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReactiveChannelManager {
    Uni<Channel> create(ChannelCreateRequest request);
    Uni<FindOrCreateResult> findOrCreate(ChannelCreateRequest request);
    Uni<Long> delete(UUID channelId, boolean force);
    Uni<Channel> pause(UUID channelId);
    Uni<Channel> resume(UUID channelId);

    Uni<Channel> setTypeConstraints(UUID channelId, Set<MessageType> allowedTypes, Set<MessageType> deniedTypes);
    Uni<Channel> setRateLimits(UUID channelId, Integer perChannel, Integer perInstance);
    Uni<Channel> setAllowedWriters(UUID channelId, List<String> allowedWriters);
    Uni<Channel> setAdminInstances(UUID channelId, List<String> adminInstances);

    Uni<Channel> setReviewerInstances(UUID channelId, List<String> reviewerInstances);

    Uni<Channel> setProtocols(UUID channelId, List<String> protocols);

    Uni<Channel> setProtocolParticipants(UUID channelId, List<String> protocolParticipants);


}

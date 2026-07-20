package io.casehub.qhorus.api.channel;

import io.casehub.qhorus.api.message.MessageType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ChannelManager {
    Channel create(ChannelCreateRequest request);
    FindOrCreateResult findOrCreate(ChannelCreateRequest request);
    long delete(UUID channelId, boolean force);
    Channel pause(UUID channelId);
    Channel resume(UUID channelId);

    Channel setTypeConstraints(UUID channelId, Set<MessageType> allowedTypes, Set<MessageType> deniedTypes);
    Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance);
    Channel setAllowedWriters(UUID channelId, List<String> allowedWriters);
    Channel setAdminInstances(UUID channelId, List<String> adminInstances);

    Channel setReviewerInstances(UUID channelId, List<String> reviewerInstances);

    Channel setProtocols(UUID channelId, List<String> protocols);

    Channel setProtocolParticipants(UUID channelId, List<String> protocolParticipants);


}

package io.casehub.qhorus.api.channel;

import java.util.UUID;

public record ChannelSummaryUpdatedEvent(UUID channelId, String channelName, String updatedBy) {}

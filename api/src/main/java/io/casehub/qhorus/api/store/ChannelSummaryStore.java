package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.ChannelSummary;

import java.util.Optional;
import java.util.UUID;

public interface ChannelSummaryStore {

    ChannelSummary save(ChannelSummary summary);

    Optional<ChannelSummary> findByChannelId(UUID channelId);

    void deleteByChannelId(UUID channelId);
}

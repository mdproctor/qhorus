package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.smallrye.mutiny.Uni;

import java.util.Optional;
import java.util.UUID;

public interface ReactiveChannelSummaryStore {

    Uni<ChannelSummary> save(ChannelSummary summary);

    Uni<Optional<ChannelSummary>> findByChannelId(UUID channelId);

    Uni<Void> deleteByChannelId(UUID channelId);
}

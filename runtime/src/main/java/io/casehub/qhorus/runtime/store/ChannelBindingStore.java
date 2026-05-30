package io.casehub.qhorus.runtime.store;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;

public interface ChannelBindingStore {

    Optional<ChannelConnectorBinding> findByChannelId(UUID channelId);

    Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey);

    void put(ChannelConnectorBinding binding);

    void delete(UUID channelId);

    /** Returns a snapshot of all bindings keyed by channelId. Callers must not mutate the map. */
    Map<UUID, ChannelConnectorBinding> findAll();
}

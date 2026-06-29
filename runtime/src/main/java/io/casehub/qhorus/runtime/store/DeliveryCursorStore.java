package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.gateway.DeliveryCursor;

public interface DeliveryCursorStore {

    DeliveryCursor save(DeliveryCursor cursor);

    Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId);

    List<DeliveryCursor> findByChannel(UUID channelId);

    List<DeliveryCursor> findAll();

    void deleteByChannel(UUID channelId);
}

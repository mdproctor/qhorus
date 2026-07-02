package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.gateway.DeliveryCursor;

public interface DeliveryCursorStore {

    DeliveryCursor save(DeliveryCursor cursor);

    Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId);

    List<DeliveryCursor> findByChannel(UUID channelId);

    List<DeliveryCursor> findAll();

    void deleteByChannel(UUID channelId);
}

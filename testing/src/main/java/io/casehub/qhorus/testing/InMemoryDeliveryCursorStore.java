package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.store.DeliveryCursorStore;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryDeliveryCursorStore implements DeliveryCursorStore {

    private final Map<UUID, DeliveryCursor> byId = new LinkedHashMap<>();

    @Override
    public DeliveryCursor save(DeliveryCursor c) {
        DeliveryCursor toSave = c;
        if (c.id() == null) {
            toSave = c.toBuilder().id(UUID.randomUUID()).build();
        }
        if (toSave.createdAt() == null) {
            toSave = toSave.toBuilder().createdAt(Instant.now()).build();
        }
        byId.put(toSave.id(), toSave);
        return toSave;
    }

    @Override
    public Optional<DeliveryCursor> findByChannelAndBackend(UUID channelId, String backendId) {
        return byId.values().stream()
                .filter(c -> channelId.equals(c.channelId()) && backendId.equals(c.backendId()))
                .findFirst();
    }

    @Override
    public List<DeliveryCursor> findByChannel(UUID channelId) {
        return byId.values().stream()
                .filter(c -> channelId.equals(c.channelId()))
                .toList();
    }

    @Override
    public List<DeliveryCursor> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public void deleteByChannel(UUID channelId) {
        byId.values().removeIf(c -> channelId.equals(c.channelId()));
    }

    public void clear() {
        byId.clear();
    }
}

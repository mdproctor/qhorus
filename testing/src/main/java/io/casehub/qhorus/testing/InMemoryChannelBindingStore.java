package io.casehub.qhorus.testing;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryChannelBindingStore implements ChannelBindingStore {

    private final ConcurrentHashMap<UUID, ChannelConnectorBinding> byChannelId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChannelConnectorBinding> byKey = new ConcurrentHashMap<>();

    private static String compoundKey(String inboundConnectorId, String externalKey) {
        return inboundConnectorId + " " + externalKey;
    }

    @Override
    public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
        return Optional.ofNullable(byChannelId.get(channelId));
    }

    @Override
    public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) {
        return Optional.ofNullable(byKey.get(compoundKey(inboundConnectorId, externalKey)));
    }

    @Override
    public void put(ChannelConnectorBinding binding) {
        ChannelConnectorBinding existing = byChannelId.get(binding.channelId);
        if (existing != null) {
            byKey.remove(compoundKey(existing.inboundConnectorId, existing.externalKey));
        }
        byChannelId.put(binding.channelId, binding);
        byKey.put(compoundKey(binding.inboundConnectorId, binding.externalKey), binding);
    }

    @Override
    public void delete(UUID channelId) {
        ChannelConnectorBinding existing = byChannelId.remove(channelId);
        if (existing != null) {
            byKey.remove(compoundKey(existing.inboundConnectorId, existing.externalKey));
        }
    }

    @Override
    public Map<UUID, ChannelConnectorBinding> findAll() {
        return Map.copyOf(byChannelId);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        byChannelId.clear();
        byKey.clear();
    }
}

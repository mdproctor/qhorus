package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryChannelSummaryStore implements ChannelSummaryStore {

    private final Map<UUID, ChannelSummary> byChannelId = new ConcurrentHashMap<>();

    @Override
    public ChannelSummary save(ChannelSummary summary) {
        ChannelSummary.Builder b = summary.toBuilder();
        if (summary.id() == null) {
            b.id(UUID.randomUUID());
        }
        ChannelSummary saved = b.build();
        byChannelId.put(saved.channelId(), saved);
        return saved;
    }

    @Override
    public Optional<ChannelSummary> findByChannelId(UUID channelId) {
        return Optional.ofNullable(byChannelId.get(channelId));
    }

    @Override
    public void deleteByChannelId(UUID channelId) {
        byChannelId.remove(channelId);
    }

    public java.util.List<ChannelSummary> findAll() {
        return java.util.List.copyOf(byChannelId.values());
    }

    public void clear() {
        byChannelId.clear();
    }
}

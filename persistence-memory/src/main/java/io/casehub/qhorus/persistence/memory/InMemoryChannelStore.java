package io.casehub.qhorus.persistence.memory;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryChannelStore implements ChannelStore {

    private final Map<UUID, Channel> store = new ConcurrentHashMap<>();

    @Override
    public Channel put(Channel channel) {
        Instant now = Instant.now();
        Channel.Builder b = channel.toBuilder();
        if (channel.id() == null) {
            b.id(UUID.randomUUID());
        }
        if (channel.createdAt() == null) {
            b.createdAt(now);
        }
        if (channel.lastActivityAt() == null) {
            b.lastActivityAt(now);
        }
        if (channel.tenancyId() == null) {
            b.tenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        }
        channel = b.build();
        store.put(channel.id(), channel);
        return channel;
    }

    @Override
    public Optional<Channel> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return store.values().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst();
    }

    @Override
    public List<Channel> scan(ChannelQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public void delete(UUID id) {
        store.remove(id);
    }

    @Override
    public void updateLastActivity(UUID channelId, String tenancyId) {
        // No-op — InMemory stores don't need lastActivityAt tracking.
        // lastActivityAt is set at creation time via put(); staleness tracking is irrelevant in tests.
    }

    @Override
    public void updateTrackDelivery(UUID channelId, Boolean trackDelivery) {
        find(channelId).ifPresent(ch -> {
            Channel updated = ch.toBuilder().trackDelivery(trackDelivery).build();
            store.put(channelId, updated);
        });
    }


    @Override
    public List<Channel> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(this::find)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    /** All channels with no tenant filter — for cross-tenant delegation. */
    List<Channel> scanAll() {
        return List.copyOf(store.values());
    }

    /** Find a channel by UUID with no tenant filter — for cross-tenant delegation. */
    Optional<Channel> findCrossTenant(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
    }
}

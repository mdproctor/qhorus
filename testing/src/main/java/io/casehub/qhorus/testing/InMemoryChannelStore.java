package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryChannelStore implements ChannelStore {

    private final Map<UUID, Channel> store = new LinkedHashMap<>();

    @Override
    public Channel put(Channel channel) {
        Instant now = Instant.now();
        UUID id = channel.id();
        if (id == null) {
            id = UUID.randomUUID();
            channel = channel.toBuilder().id(id).build();
        }
        if (channel.createdAt() == null) {
            channel = channel.toBuilder().createdAt(now).build();
        }
        if (channel.lastActivityAt() == null) {
            channel = channel.toBuilder().lastActivityAt(now).build();
        }
        if (channel.tenancyId() == null) {
            channel = channel.toBuilder().tenancyId(TenancyConstants.DEFAULT_TENANT_ID).build();
        }
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
        // No-op — modifying the Hibernate-enhanced entity in-place triggers a dirty-check
        // flush when called from within Panache.withSession(), generating a spurious JPA UPDATE.
        // lastActivityAt is set at creation time via put(); staleness tracking is irrelevant in tests.
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

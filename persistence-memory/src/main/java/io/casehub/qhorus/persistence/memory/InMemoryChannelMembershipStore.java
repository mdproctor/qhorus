package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryChannelMembershipStore implements ChannelMembershipStore {

    private final Map<Long, ChannelMembership> store = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    @Override
    public ChannelMembership put(ChannelMembership membership) {
        ChannelMembership saved = new ChannelMembership(
                idCounter.getAndIncrement(), membership.channelId(), membership.memberId(),
                membership.role(), membership.tenancyId(), membership.joinedAt(),
                membership.lastReadMessageId(), membership.lastDeliveredMessageId());
        store.put(saved.id(), saved);
        return saved;}

    @Override
    public Optional<ChannelMembership> find(UUID channelId, String memberId) {
        return store.values().stream()
                .filter(m -> m.channelId().equals(channelId) && m.memberId().equals(memberId))
                .findFirst();
    }

    @Override
    public List<ChannelMembership> findByChannel(UUID channelId) {
        return store.values().stream()
                .filter(m -> m.channelId().equals(channelId))
                .sorted((a, b) -> a.joinedAt().compareTo(b.joinedAt()))
                .toList();
    }

    @Override
    public List<ChannelMembership> findByMember(String memberId, String tenancyId) {
        return store.values().stream()
                .filter(m -> m.memberId().equals(memberId)
                        && (tenancyId == null || m.tenancyId().equals(tenancyId)))
                .toList();
    }

    @Override
    public void updateRole(UUID channelId, String memberId, MemberRole role) {
        find(channelId, memberId).ifPresent(existing -> {
            ChannelMembership updated = new ChannelMembership(
                    existing.id(), existing.channelId(), existing.memberId(),
                    role, existing.tenancyId(), existing.joinedAt(),
                    existing.lastReadMessageId(), existing.lastDeliveredMessageId());
            store.put(updated.id(), updated);
        });}

    @Override
    public void updateLastReadMessageId(UUID channelId, String memberId, Long messageId) {
        find(channelId, memberId).ifPresent(existing -> {
            ChannelMembership updated = new ChannelMembership(
                    existing.id(), existing.channelId(), existing.memberId(),
                    existing.role(), existing.tenancyId(), existing.joinedAt(),
                    messageId, existing.lastDeliveredMessageId());
            store.put(updated.id(), updated);
        });}

    @Override
    public void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId) {
        find(channelId, memberId).ifPresent(existing -> {
            if (existing.lastDeliveredMessageId() == null || messageId > existing.lastDeliveredMessageId()) {
                ChannelMembership updated = new ChannelMembership(
                        existing.id(), existing.channelId(), existing.memberId(),
                        existing.role(), existing.tenancyId(), existing.joinedAt(),
                        existing.lastReadMessageId(), messageId);
                store.put(updated.id(), updated);
            }
        });
    }

    @Override
    public void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId) {
        for (String memberId : memberIds) {
            updateLastDeliveredMessageId(channelId, memberId, messageId);
        }
    }


    @Override
    public boolean delete(UUID channelId, String memberId) {
        Optional<ChannelMembership> existing = find(channelId, memberId);
        existing.ifPresent(m -> store.remove(m.id()));
        return existing.isPresent();
    }

    @Override
    public void deleteAll(UUID channelId) {
        store.values().removeIf(m -> m.channelId().equals(channelId));
    }

    public void clear() {
        store.clear();
    }
}

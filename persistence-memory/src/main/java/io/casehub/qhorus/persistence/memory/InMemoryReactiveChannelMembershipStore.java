package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.api.store.ReactiveChannelMembershipStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryReactiveChannelMembershipStore implements ReactiveChannelMembershipStore {

    private final InMemoryChannelMembershipStore delegate = new InMemoryChannelMembershipStore();

    @Override
    public Uni<ChannelMembership> put(ChannelMembership membership) {
        return Uni.createFrom().item(() -> delegate.put(membership));
    }

    @Override
    public Uni<Optional<ChannelMembership>> find(UUID channelId, String memberId) {
        return Uni.createFrom().item(() -> delegate.find(channelId, memberId));
    }

    @Override
    public Uni<List<ChannelMembership>> findByChannel(UUID channelId) {
        return Uni.createFrom().item(() -> delegate.findByChannel(channelId));
    }

    @Override
    public Uni<List<ChannelMembership>> findByMember(String memberId, String tenancyId) {
        return Uni.createFrom().item(() -> delegate.findByMember(memberId, tenancyId));
    }

    @Override
    public Uni<Void> updateRole(UUID channelId, String memberId, MemberRole role) {
        return Uni.createFrom().item(() -> { delegate.updateRole(channelId, memberId, role); return null; });
    }

    @Override
    public Uni<Void> updateLastReadMessageId(UUID channelId, String memberId, Long messageId) {
        return Uni.createFrom().item(() -> { delegate.updateLastReadMessageId(channelId, memberId, messageId); return null; });
    }

    @Override
    public Uni<Boolean> delete(UUID channelId, String memberId) {
        return Uni.createFrom().item(() -> delegate.delete(channelId, memberId));
    }

    @Override
    public Uni<Void> deleteAll(UUID channelId) {
        return Uni.createFrom().item(() -> { delegate.deleteAll(channelId); return null; });
    }

    public InMemoryChannelMembershipStore delegate() {return delegate;}
}

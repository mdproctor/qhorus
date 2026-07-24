package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveChannelMembershipStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class InMemoryReactiveChannelMembershipStoreTest extends ChannelMembershipStoreContractTest {

    private final InMemoryReactiveChannelMembershipStore store = new InMemoryReactiveChannelMembershipStore();

    @Override protected ChannelMembership put(ChannelMembership m) { return store.put(m).await().indefinitely(); }
    @Override protected Optional<ChannelMembership> find(UUID channelId, String memberId) { return store.find(channelId, memberId).await().indefinitely(); }
    @Override protected List<ChannelMembership> findByChannel(UUID channelId) { return store.findByChannel(channelId).await().indefinitely(); }
    @Override protected List<ChannelMembership> findByMember(String memberId, String tenancyId) { return store.findByMember(memberId, tenancyId).await().indefinitely(); }
    @Override protected void updateRole(UUID channelId, String memberId, MemberRole role) { store.updateRole(channelId, memberId, role).await().indefinitely(); }
    @Override protected void updateLastReadMessageId(UUID channelId, String memberId, Long messageId) { store.updateLastReadMessageId(channelId, memberId, messageId).await().indefinitely(); }

    @Override
    protected void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId)      {store.delegate().updateLastDeliveredMessageId(channelId, memberId, messageId);}

    @Override
    protected void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId) {store.delegate().advanceDeliveredCursorForMembers(channelId, memberIds, messageId);}

    @Override protected boolean delete(UUID channelId, String memberId) { return store.delete(channelId, memberId).await().indefinitely(); }
    @Override protected void deleteAll(UUID channelId) { store.deleteAll(channelId).await().indefinitely(); }
}

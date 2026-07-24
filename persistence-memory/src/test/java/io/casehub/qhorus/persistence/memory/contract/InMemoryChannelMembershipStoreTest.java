package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.persistence.memory.InMemoryChannelMembershipStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class InMemoryChannelMembershipStoreTest extends ChannelMembershipStoreContractTest {

    private final InMemoryChannelMembershipStore store = new InMemoryChannelMembershipStore();

    @Override protected ChannelMembership put(ChannelMembership m) { return store.put(m); }
    @Override protected Optional<ChannelMembership> find(UUID channelId, String memberId) { return store.find(channelId, memberId); }
    @Override protected List<ChannelMembership> findByChannel(UUID channelId) { return store.findByChannel(channelId); }
    @Override protected List<ChannelMembership> findByMember(String memberId, String tenancyId) { return store.findByMember(memberId, tenancyId); }
    @Override protected void updateRole(UUID channelId, String memberId, MemberRole role) { store.updateRole(channelId, memberId, role); }
    @Override protected void updateLastReadMessageId(UUID channelId, String memberId, Long messageId) { store.updateLastReadMessageId(channelId, memberId, messageId); }

    @Override
    protected void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId)      {store.updateLastDeliveredMessageId(channelId, memberId, messageId);}

    @Override
    protected void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId) {store.advanceDeliveredCursorForMembers(channelId, memberIds, messageId);}

    @Override protected boolean delete(UUID channelId, String memberId) { return store.delete(channelId, memberId); }
    @Override protected void deleteAll(UUID channelId) { store.deleteAll(channelId); }
}

package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelMembershipStore {
    ChannelMembership put(ChannelMembership membership);
    Optional<ChannelMembership> find(UUID channelId, String memberId);
    List<ChannelMembership> findByChannel(UUID channelId);
    List<ChannelMembership> findByMember(String memberId, String tenancyId);
    void updateRole(UUID channelId, String memberId, MemberRole role);
    void updateLastReadMessageId(UUID channelId, String memberId, Long messageId);

    void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId);

    void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId);

    boolean delete(UUID channelId, String memberId);
    void deleteAll(UUID channelId);
}

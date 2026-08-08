package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;

import java.util.UUID;

public interface ChannelMembershipStore extends MembershipReader {
    ChannelMembership put(ChannelMembership membership);

    void updateRole(UUID channelId, String memberId, MemberRole role);

    void updateLastReadMessageId(UUID channelId, String memberId, Long messageId);

    void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId);

    void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId);

    default java.util.List<io.casehub.qhorus.api.channel.ChannelMembership> findWithDeliveryLag(
            java.util.UUID channelId, Long backendCursorId) {
        return findByChannel(channelId).stream()
                                       .filter(m -> m.lastDeliveredMessageId() != null
                                                    && m.lastDeliveredMessageId() < backendCursorId)
                                       .toList();
    }


    boolean delete(UUID channelId, String memberId);

    void deleteAll(UUID channelId);
}

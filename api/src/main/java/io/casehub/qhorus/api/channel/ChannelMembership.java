package io.casehub.qhorus.api.channel;

import java.time.Instant;
import java.util.UUID;

public record ChannelMembership(
        Long id,
        UUID channelId,
        String memberId,
        MemberRole role,
        String tenancyId,
        Instant joinedAt,
        Long lastReadMessageId,
        Long lastDeliveredMessageId) {

    public ChannelMembership(Long id, UUID channelId, String memberId, MemberRole role,
                             String tenancyId, Instant joinedAt, Long lastReadMessageId) {
        this(id, channelId, memberId, role, tenancyId, joinedAt, lastReadMessageId, null);
    }
}

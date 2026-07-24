package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
import io.casehub.qhorus.runtime.channel.ChannelMembershipEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaChannelMembershipStore implements ChannelMembershipStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    public ChannelMembership put(ChannelMembership membership) {
        ChannelMembershipEntity e = ChannelMembershipEntity.fromDomain(membership);
        e.persist();
        return e.toDomain();
    }

    @Override
    public Optional<ChannelMembership> find(UUID channelId, String memberId) {
        return ChannelMembershipEntity.<ChannelMembershipEntity>find(
                "channelId = ?1 AND memberId = ?2", channelId, memberId)
                .firstResultOptional()
                .map(ChannelMembershipEntity::toDomain);
    }

    @Override
    public List<ChannelMembership> findByChannel(UUID channelId) {
        return ChannelMembershipEntity.<ChannelMembershipEntity>find(
                "channelId = ?1 ORDER BY joinedAt", channelId)
                .list()
                .stream()
                .map(ChannelMembershipEntity::toDomain)
                .toList();
    }

    @Override
    public List<ChannelMembership> findByMember(String memberId, String tenancyId) {
        String tid = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
        return ChannelMembershipEntity.<ChannelMembershipEntity>find(
                "memberId = ?1 AND tenancyId = ?2 ORDER BY joinedAt", memberId, tid)
                .list()
                .stream()
                .map(ChannelMembershipEntity::toDomain)
                .toList();
    }

    @Override
    public void updateRole(UUID channelId, String memberId, MemberRole role) {
        ChannelMembershipEntity.update("memberRole = ?1 WHERE channelId = ?2 AND memberId = ?3",
                role, channelId, memberId);
    }

    @Override
    public void updateLastReadMessageId(UUID channelId, String memberId, Long messageId) {
        ChannelMembershipEntity.update("lastReadMessageId = ?1 WHERE channelId = ?2 AND memberId = ?3",
                messageId, channelId, memberId);
    }

    @Override
    public void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId) {
        ChannelMembershipEntity.<ChannelMembershipEntity>find(
                                       "channelId = ?1 AND memberId = ?2", channelId, memberId)
                               .firstResultOptional()
                               .ifPresent(e -> {
                                   if (e.lastDeliveredMessageId == null || messageId > e.lastDeliveredMessageId) {
                                       e.lastDeliveredMessageId = messageId;
                                   }
                               });
    }

    @Override
    public void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId) {
        if (memberIds.isEmpty()) {return;}
        ChannelMembershipEntity.<ChannelMembershipEntity>find(
                                       "channelId = ?1 AND memberId IN ?2", channelId, memberIds)
                               .list()
                               .forEach(e -> {
                                   if (e.lastDeliveredMessageId == null || messageId > e.lastDeliveredMessageId) {
                                       e.lastDeliveredMessageId = messageId;
                                   }
                               });
    }


    @Override
    public boolean delete(UUID channelId, String memberId) {
        return ChannelMembershipEntity.delete("channelId = ?1 AND memberId = ?2", channelId, memberId) > 0;
    }

    @Override
    public void deleteAll(UUID channelId) {
        ChannelMembershipEntity.delete("channelId = ?1", channelId);
    }
}

package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
import io.casehub.qhorus.runtime.channel.ChannelMembershipEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaChannelMembershipStore implements ChannelMembershipStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public ChannelMembership put(ChannelMembership membership) {
        ChannelMembershipEntity e = ChannelMembershipEntity.fromDomain(membership);
        em.persist(e);
        return e.toDomain();
    }

    @Override
    public Optional<ChannelMembership> find(UUID channelId, String memberId) {
        return em.createQuery("SELECT e FROM ChannelMembership e WHERE e.channelId = ?1 AND e.memberId = ?2", ChannelMembershipEntity.class)
                .setParameter(1, channelId).setParameter(2, memberId)
                .getResultStream().findFirst()
                .map(ChannelMembershipEntity::toDomain);
    }

    @Override
    public List<ChannelMembership> findByChannel(UUID channelId) {
        return em.createQuery("SELECT e FROM ChannelMembership e WHERE e.channelId = ?1 ORDER BY e.joinedAt", ChannelMembershipEntity.class)
                .setParameter(1, channelId)
                .getResultList()
                .stream()
                .map(ChannelMembershipEntity::toDomain)
                .toList();
    }

    @Override
    public List<ChannelMembership> findByMember(String memberId, String tenancyId) {
        String tid = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
        return em.createQuery("SELECT e FROM ChannelMembership e WHERE e.memberId = ?1 AND e.tenancyId = ?2 ORDER BY e.joinedAt", ChannelMembershipEntity.class)
                .setParameter(1, memberId).setParameter(2, tid)
                .getResultList()
                .stream()
                .map(ChannelMembershipEntity::toDomain)
                .toList();
    }

    @Override
    public void updateRole(UUID channelId, String memberId, MemberRole role) {
        em.createQuery("UPDATE ChannelMembership e SET e.memberRole = ?1 WHERE e.channelId = ?2 AND e.memberId = ?3")
                .setParameter(1, role).setParameter(2, channelId).setParameter(3, memberId).executeUpdate();
    }

    @Override
    public void updateLastReadMessageId(UUID channelId, String memberId, Long messageId) {
        em.createQuery("UPDATE ChannelMembership e SET e.lastReadMessageId = ?1 WHERE e.channelId = ?2 AND e.memberId = ?3")
                .setParameter(1, messageId).setParameter(2, channelId).setParameter(3, memberId).executeUpdate();
    }

    @Override
    public void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId) {
        em.createQuery("SELECT e FROM ChannelMembership e WHERE e.channelId = ?1 AND e.memberId = ?2", ChannelMembershipEntity.class)
                               .setParameter(1, channelId).setParameter(2, memberId)
                               .getResultStream().findFirst()
                               .ifPresent(e -> {
                                   if (e.lastDeliveredMessageId == null || messageId > e.lastDeliveredMessageId) {
                                       e.lastDeliveredMessageId = messageId;
                                   }
                               });
    }

    @Override
    public void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId) {
        if (memberIds.isEmpty()) {return;}
        em.createQuery("SELECT e FROM ChannelMembership e WHERE e.channelId = ?1 AND e.memberId IN ?2", ChannelMembershipEntity.class)
                               .setParameter(1, channelId).setParameter(2, memberIds)
                               .getResultList()
                               .forEach(e -> {
                                   if (e.lastDeliveredMessageId == null || messageId > e.lastDeliveredMessageId) {
                                       e.lastDeliveredMessageId = messageId;
                                   }
                               });
    }


    @Override
    public boolean delete(UUID channelId, String memberId) {
        return em.createQuery("DELETE FROM ChannelMembership e WHERE e.channelId = ?1 AND e.memberId = ?2")
                .setParameter(1, channelId).setParameter(2, memberId).executeUpdate() > 0;
    }

    @Override
    public void deleteAll(UUID channelId) {
        em.createQuery("DELETE FROM ChannelMembership e WHERE e.channelId = ?1").setParameter(1, channelId).executeUpdate();
    }
}

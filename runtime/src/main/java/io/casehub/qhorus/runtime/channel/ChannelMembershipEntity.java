package io.casehub.qhorus.runtime.channel;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "ChannelMembership")
@Table(name = "channel_membership")
public class ChannelMembershipEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "member_id", nullable = false)
    public String memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false)
    public MemberRole memberRole;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId = TenancyConstants.DEFAULT_TENANT_ID;

    @Column(name = "joined_at", nullable = false, updatable = false)
    public Instant joinedAt;

    @Column(name = "last_read_message_id")
    public Long lastReadMessageId;
    @Column(name = "last_delivered_message_id")
    public Long lastDeliveredMessageId;


    @PrePersist
    void prePersist() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public static ChannelMembershipEntity fromDomain(ChannelMembership m) {
        ChannelMembershipEntity e = new ChannelMembershipEntity();
        e.id                     = m.id();
        e.channelId              = m.channelId();
        e.memberId               = m.memberId();
        e.memberRole             = m.role();
        e.tenancyId              = m.tenancyId() != null ? m.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.joinedAt               = m.joinedAt();
        e.lastReadMessageId      = m.lastReadMessageId();
        e.lastDeliveredMessageId = m.lastDeliveredMessageId();
        return e;}

    public ChannelMembership toDomain() {return new ChannelMembership(id, channelId, memberId, memberRole, tenancyId, joinedAt, lastReadMessageId, lastDeliveredMessageId);}
}

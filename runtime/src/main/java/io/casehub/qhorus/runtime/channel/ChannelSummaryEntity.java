package io.casehub.qhorus.runtime.channel;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "ChannelSummary")
@Table(name = "channel_summary")
public class ChannelSummaryEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "channel_id", nullable = false, unique = true)
    public UUID channelId;

    @Column(name = "content", columnDefinition = "TEXT")
    public String content;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "updated_by")
    public String updatedBy;

    @Column(name = "last_updated_message_id")
    public Long lastUpdatedMessageId;

    @Column(name = "update_after_messages")
    public Integer updateAfterMessages;

    @Column(name = "update_after_seconds")
    public Integer updateAfterSeconds;

    @Column(name = "tenancy_id", nullable = false, updatable = false)
    public String tenancyId = TenancyConstants.DEFAULT_TENANT_ID;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public static ChannelSummaryEntity fromDomain(ChannelSummary s) {
        ChannelSummaryEntity e = new ChannelSummaryEntity();
        e.id = s.id();
        e.channelId = s.channelId();
        e.content = s.content();
        e.updatedAt = s.updatedAt();
        e.updatedBy = s.updatedBy();
        e.lastUpdatedMessageId = s.lastUpdatedMessageId();
        e.updateAfterMessages = s.updateAfterMessages();
        e.updateAfterSeconds = s.updateAfterSeconds();
        e.tenancyId = s.tenancyId() != null ? s.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        return e;
    }

    public ChannelSummary toDomain() {
        return new ChannelSummary(id, channelId, content, updatedAt, updatedBy,
                lastUpdatedMessageId, updateAfterMessages, updateAfterSeconds, tenancyId);
    }
}

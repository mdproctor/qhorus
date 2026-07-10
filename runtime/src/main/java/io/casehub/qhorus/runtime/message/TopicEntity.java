package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.UUID;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.Topic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity(name = "Topic")
@Table(name = "topic")
public class TopicEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false)
    public boolean resolved;

    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Column(name = "resolved_by")
    public String resolvedBy;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId = TenancyConstants.DEFAULT_TENANT_ID;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static TopicEntity fromDomain(Topic topic) {
        TopicEntity e = new TopicEntity();
        e.id = topic.id();
        e.channelId = topic.channelId();
        e.name = topic.name();
        e.resolved = topic.resolved();
        e.resolvedAt = topic.resolvedAt();
        e.resolvedBy = topic.resolvedBy();
        e.tenancyId = topic.tenancyId() != null ? topic.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.createdAt = topic.createdAt();
        return e;
    }

    public Topic toDomain() {
        return new Topic(id, channelId, name, resolved, resolvedAt, resolvedBy, createdAt, tenancyId);
    }
}

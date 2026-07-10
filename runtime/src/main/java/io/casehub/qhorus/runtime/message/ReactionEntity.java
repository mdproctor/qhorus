package io.casehub.qhorus.runtime.message;

import java.time.Instant;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.Reaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity(name = "Reaction")
@Table(name = "reaction")
public class ReactionEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "message_id", nullable = false)
    public Long messageId;

    @Column(nullable = false, length = 100)
    public String emoji;

    @Column(name = "actor_id", nullable = false)
    public String actorId;

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

    public static ReactionEntity fromDomain(Reaction r) {
        ReactionEntity e = new ReactionEntity();
        e.id = r.id();
        e.messageId = r.messageId();
        e.emoji = r.emoji();
        e.actorId = r.actorId();
        e.tenancyId = r.tenancyId() != null ? r.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.createdAt = r.createdAt();
        return e;
    }

    public Reaction toDomain() {
        return new Reaction(id, messageId, emoji, actorId, createdAt, tenancyId);
    }
}

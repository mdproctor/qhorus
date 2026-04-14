package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "pending_reply", uniqueConstraints = @UniqueConstraint(name = "uq_pending_reply_corr_id", columnNames = "correlation_id"))
public class PendingReply extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "correlation_id", nullable = false)
    public String correlationId;

    @Column(name = "instance_id")
    public UUID instanceId;

    @Column(name = "channel_id")
    public UUID channelId;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}

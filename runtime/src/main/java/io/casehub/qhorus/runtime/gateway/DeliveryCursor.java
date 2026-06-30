package io.casehub.qhorus.runtime.gateway;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "delivery_cursor",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_delivery_cursor_channel_backend",
           columnNames = {"channel_id", "backend_id"}))
public class DeliveryCursor extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "backend_id", nullable = false)
    public String backendId;

    @Column(name = "last_delivered_id")
    public Long lastDeliveredId;

    @Column(name = "last_delivered_version", nullable = false)
    public int lastDeliveredVersion = 0;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

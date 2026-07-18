package io.casehub.qhorus.runtime.watchdog;

import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A condition-based watchdog that fires alert messages to a notification channel
 * when the condition threshold is exceeded.
 *
 * <p>
 * Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH.
 * Only active when {@code casehub.qhorus.watchdog.enabled=true}.
 */
@Entity(name = "Watchdog")
@Table(name = "watchdog")
public class WatchdogEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "condition_type", nullable = false)
    public String conditionType;

    @Column(name = "target_name", nullable = false)
    public String targetName;

    @Column(name = "threshold_seconds")
    public Integer thresholdSeconds;

    @Column(name = "threshold_count")
    public Integer thresholdCount;
    @Column(name = "similarity_pct")
    public Integer similarityPct;


    @Column(name = "notification_channel", nullable = false)
    public String notificationChannel;

    @Column(name = "created_by")
    public String createdBy;

    /* default = single-tenant sentinel; overridden by QhorusMcpTools (Task 11); PP-20260520-e6a5f0 */
    @Column(name = "tenancy_id", nullable = false, updatable = false)
    public String tenancyId = "278776f9-e1b0-46fb-9032-8bddebdcf9ce"; // TenancyConstants.DEFAULT_TENANT_ID

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_fired_at")
    public Instant lastFiredAt;

    public static WatchdogEntity fromDomain(io.casehub.qhorus.api.watchdog.Watchdog w) {
        WatchdogEntity e = new WatchdogEntity();
        e.id                  = w.id();
        e.conditionType       = w.conditionType().name();
        e.targetName          = w.targetName();
        e.thresholdSeconds    = w.thresholdSeconds();
        e.thresholdCount      = w.thresholdCount();
        e.similarityPct       = w.similarityPct();
        e.notificationChannel = w.notificationChannel();
        e.createdBy           = w.createdBy();
        e.tenancyId           = w.tenancyId() != null ? w.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.createdAt           = w.createdAt();
        e.lastFiredAt         = w.lastFiredAt();
        return e;}

    public io.casehub.qhorus.api.watchdog.Watchdog toDomain() {
        io.casehub.qhorus.api.watchdog.WatchdogConditionType type =
                io.casehub.qhorus.api.watchdog.WatchdogConditionType.fromString(conditionType).orElse(null);
        if (type == null) {
            return null;
        }
        return new io.casehub.qhorus.api.watchdog.Watchdog(
                id, type, targetName, thresholdSeconds, thresholdCount,
                similarityPct, notificationChannel, createdBy, tenancyId,
                createdAt, lastFiredAt);}

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

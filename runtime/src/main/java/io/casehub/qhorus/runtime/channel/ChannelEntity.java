package io.casehub.qhorus.runtime.channel;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "Channel")
@Table(name = "channel", uniqueConstraints = @UniqueConstraint(name = "uq_channel_name_tenancy", columnNames = { "tenancy_id", "name" }))
public class ChannelEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(nullable = false, updatable = false) /* immutable after creation — PP-20260604-dualid */
    public String name;

    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ChannelSemantic semantic;

    @Column(name = "barrier_contributors", columnDefinition = "TEXT")
    public String barrierContributors;

    /**
     * Comma-separated list of allowed writers. Each entry is a bare instance ID, or a
     * {@code capability:tag} / {@code role:name} pattern. Null = open (any writer permitted).
     */
    @Column(name = "allowed_writers", columnDefinition = "TEXT")
    public String allowedWriters;

    /**
     * Comma-separated list of instance IDs permitted to invoke management operations
     * (pause/resume/force_release/clear_channel). Null = open governance (any caller permitted).
     */
    @Column(name = "admin_instances", columnDefinition = "TEXT")
    public String adminInstances;
    @Column(name = "reviewer_instances", columnDefinition = "TEXT")
    public String reviewerInstances;
    @Column(name = "protocols", columnDefinition = "TEXT")
    public String protocols;
    @Column(name = "protocol_participants", columnDefinition = "TEXT")
    public String protocolParticipants;


    /** Max messages per minute across all senders on this channel. Null = unlimited. */
    @Column(name = "rate_limit_per_channel")
    public Integer rateLimitPerChannel;

    /** Max messages per minute from a single sender on this channel. Null = unlimited. */
    @Column(name = "rate_limit_per_instance")
    public Integer rateLimitPerInstance;

    /**
     * Comma-separated list of permitted MessageType names.
     * Null means all types are permitted (open channel).
     * Example: "EVENT" for a telemetry-only observe channel.
     */
    @Column(name = "allowed_types", columnDefinition = "TEXT")
    public String allowedTypes;

    /**
     * Comma-separated list of denied MessageType names.
     * Null means no types are explicitly denied.
     * If a type appears in both allowedTypes and deniedTypes, denial wins.
     * Example: "EVENT" for a governance channel that must not contain telemetry.
     * If a new MessageType is added to Qhorus with no commitment effect (like EVENT),
     * add it here for all governance channels — this is the mechanical anchor for that obligation.
     */
    @Column(name = "denied_types", columnDefinition = "TEXT")
    public String deniedTypes;

    /** When true, send_message is blocked and check_messages returns empty + paused status. */
    @Column(nullable = false)
    public boolean paused = false;

    /** True when this channel was auto-created by ConnectorChannelBackend on first contact. */
    @Column(name = "auto_created", nullable = false)
    public boolean autoCreated = false;
    @Column(name = "space_id")
    public UUID    spaceId;
    @Column(name = "track_delivery")
    public Boolean trackDelivery;


    /* default = single-tenant sentinel; overridden by ChannelService.create() (Task 10); PP-20260520-e6a5f0 */
    @Column(name = "tenancy_id", nullable = false, updatable = false)
    public String tenancyId = "278776f9-e1b0-46fb-9032-8bddebdcf9ce"; // TenancyConstants.DEFAULT_TENANT_ID

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    public Instant lastActivityAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastActivityAt == null) {
            lastActivityAt = now;
        }
    }

    public static ChannelEntity fromDomain(io.casehub.qhorus.api.channel.Channel channel) {
        ChannelEntity e = new ChannelEntity();
        e.id                   = channel.id();
        e.name                 = channel.name();
        e.description          = channel.description();
        e.semantic             = channel.semantic();
        e.barrierContributors  = joinCsv(channel.barrierContributors());
        e.allowedWriters       = joinCsv(channel.allowedWriters());
        e.adminInstances       = joinCsv(channel.adminInstances());
        e.reviewerInstances    = joinCsv(channel.reviewerInstances());
        e.protocols            = joinCsv(channel.protocols());
        e.protocolParticipants = joinCsv(channel.protocolParticipants());
        e.rateLimitPerChannel  = channel.rateLimitPerChannel();
        e.rateLimitPerInstance = channel.rateLimitPerInstance();
        e.allowedTypes         = MessageType.serializeTypes(channel.allowedTypes());
        e.deniedTypes          = MessageType.serializeTypes(channel.deniedTypes());
        e.paused               = channel.paused();
        e.autoCreated          = channel.autoCreated();
        e.spaceId              = channel.spaceId();
        e.trackDelivery        = channel.trackDelivery();
        e.tenancyId            = channel.tenancyId() != null ? channel.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.createdAt            = channel.createdAt();
        e.lastActivityAt       = channel.lastActivityAt();
        return e;}

    public io.casehub.qhorus.api.channel.Channel toDomain() {
        return new io.casehub.qhorus.api.channel.Channel(
                id, name, description, semantic,
                splitCsv(barrierContributors),
                splitCsv(allowedWriters),
                splitCsv(adminInstances),
                rateLimitPerChannel, rateLimitPerInstance,
                nullIfEmpty(MessageType.parseTypes(allowedTypes)),
                nullIfEmpty(MessageType.parseTypes(deniedTypes)),
                paused, autoCreated, spaceId,
                splitCsv(reviewerInstances),
                splitCsv(protocols),
                splitCsv(protocolParticipants),
                trackDelivery,
                tenancyId, createdAt, lastActivityAt);}

    private static String joinCsv(java.util.List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(",", list);
    }

    private static java.util.List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return java.util.List.of(csv.split(","));
    }

    private static <T> java.util.Set<T> nullIfEmpty(java.util.Set<T> set) {
        return (set == null || set.isEmpty()) ? null : set;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

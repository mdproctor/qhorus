package io.casehub.qhorus.runtime.instance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;


@Entity(name = "Instance")
@Table(name = "instance", uniqueConstraints = @UniqueConstraint(name = "uq_instance_instance_id", columnNames = "instance_id"))
public class InstanceEntity {

    @Id
    public UUID id;

    @Column(name = "instance_id", nullable = false)
    public String instanceId;

    public String description;

    /** online | offline | stale */
    @Column(nullable = false)
    public String status;

    @Column(name = "claudony_session_id")
    public String claudonySessionId;

    @Column(name = "session_token")
    public String sessionToken;

    @Column(name = "read_only", nullable = false)
    public boolean readOnly;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen;

    @Column(name = "registered_at", nullable = false, updatable = false)
    public Instant registeredAt;

    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;

    public static InstanceEntity fromDomain(io.casehub.qhorus.api.instance.Instance inst) {
        InstanceEntity e = new InstanceEntity();
        e.id = inst.id();
        e.instanceId = inst.instanceId();
        e.description = inst.description();
        e.status = inst.status();
        e.claudonySessionId = inst.claudonySessionId();
        e.sessionToken = inst.sessionToken();
        e.readOnly = inst.readOnly();
        e.lastSeen = inst.lastSeen();
        e.registeredAt = inst.registeredAt();
        e.metadata = serializeMap(inst.metadata());
        return e;
    }

    public io.casehub.qhorus.api.instance.Instance toDomain() {
        return new io.casehub.qhorus.api.instance.Instance(
                id, instanceId, description, status, claudonySessionId,
                sessionToken, readOnly, lastSeen, registeredAt, deserializeMap(metadata));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String serializeMap(java.util.Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try { return JSON.writeValueAsString(map); } catch (Exception e) { return null; }
    }

    private static java.util.Map<String, String> deserializeMap(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {}); } catch (Exception e) { return null; }
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (registeredAt == null) {
            registeredAt = now;
        }
        if (lastSeen == null) {
            lastSeen = now;
        }
        if (status == null) {
            status = "online";
        }
    }
}

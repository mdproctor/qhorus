package io.casehub.qhorus.api.instance;

import java.time.Instant;
import java.util.UUID;

public record Instance(
        UUID id,
        String instanceId,
        String description,
        String status,
        String claudonySessionId,
        String sessionToken,
        boolean readOnly,
        Instant lastSeen,
        Instant registeredAt) {

    public Builder toBuilder() {
        return new Builder(instanceId)
                .id(id).description(description).status(status)
                .claudonySessionId(claudonySessionId).sessionToken(sessionToken)
                .readOnly(readOnly).lastSeen(lastSeen).registeredAt(registeredAt);
    }

    public static Builder builder(String instanceId) { return new Builder(instanceId); }

    public static final class Builder {
        private final String instanceId;
        private UUID id;
        private String description;
        private String status;
        private String claudonySessionId;
        private String sessionToken;
        private boolean readOnly;
        private Instant lastSeen;
        private Instant registeredAt;

        private Builder(String instanceId) { this.instanceId = instanceId; }

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder claudonySessionId(String v) { this.claudonySessionId = v; return this; }
        public Builder sessionToken(String v) { this.sessionToken = v; return this; }
        public Builder readOnly(boolean v) { this.readOnly = v; return this; }
        public Builder lastSeen(Instant v) { this.lastSeen = v; return this; }
        public Builder registeredAt(Instant v) { this.registeredAt = v; return this; }

        public Instance build() {
            return new Instance(id, instanceId, description, status, claudonySessionId,
                    sessionToken, readOnly, lastSeen, registeredAt);
        }
    }
}

package io.casehub.qhorus.api.gateway;

import java.time.Instant;
import java.util.UUID;

public record DeliveryCursor(
        UUID id,
        UUID channelId,
        String backendId,
        Long lastDeliveredId,
        int lastDeliveredVersion,
        Instant updatedAt,
        Instant createdAt) {

    public Builder toBuilder() {
        return new Builder()
                .id(id).channelId(channelId).backendId(backendId)
                .lastDeliveredId(lastDeliveredId).lastDeliveredVersion(lastDeliveredVersion)
                .updatedAt(updatedAt).createdAt(createdAt);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID id;
        private UUID channelId;
        private String backendId;
        private Long lastDeliveredId;
        private int lastDeliveredVersion;
        private Instant updatedAt;
        private Instant createdAt;

        private Builder() {}

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder channelId(UUID v) { this.channelId = v; return this; }
        public Builder backendId(String v) { this.backendId = v; return this; }
        public Builder lastDeliveredId(Long v) { this.lastDeliveredId = v; return this; }
        public Builder lastDeliveredVersion(int v) { this.lastDeliveredVersion = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public DeliveryCursor build() {
            return new DeliveryCursor(id, channelId, backendId, lastDeliveredId,
                    lastDeliveredVersion, updatedAt, createdAt);
        }
    }
}

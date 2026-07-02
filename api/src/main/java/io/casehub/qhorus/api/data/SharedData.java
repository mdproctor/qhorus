package io.casehub.qhorus.api.data;

import java.time.Instant;
import java.util.UUID;

public record SharedData(
        UUID id,
        String key,
        String content,
        String createdBy,
        String description,
        boolean complete,
        long sizeBytes,
        Instant createdAt,
        Instant updatedAt) {

    public Builder toBuilder() {
        return new Builder(key).id(id).content(content).createdBy(createdBy)
                .description(description).complete(complete).sizeBytes(sizeBytes)
                .createdAt(createdAt).updatedAt(updatedAt);
    }

    public static Builder builder(String key) { return new Builder(key); }

    public static final class Builder {
        private final String key;
        private UUID id;
        private String content;
        private String createdBy;
        private String description;
        private boolean complete;
        private long sizeBytes;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder(String key) { this.key = key; }

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder complete(boolean v) { this.complete = v; return this; }
        public Builder sizeBytes(long v) { this.sizeBytes = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }

        public SharedData build() {
            return new SharedData(id, key, content, createdBy, description,
                    complete, sizeBytes, createdAt, updatedAt);
        }
    }
}

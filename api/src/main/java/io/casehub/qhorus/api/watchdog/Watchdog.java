package io.casehub.qhorus.api.watchdog;

import java.time.Instant;
import java.util.UUID;

public record Watchdog(
        UUID id,
        String conditionType,
        String targetName,
        Integer thresholdSeconds,
        Integer thresholdCount,
        String notificationChannel,
        String createdBy,
        String tenancyId,
        Instant createdAt,
        Instant lastFiredAt) {

    public Builder toBuilder() {
        return new Builder(conditionType, targetName).id(id)
                .thresholdSeconds(thresholdSeconds).thresholdCount(thresholdCount)
                .notificationChannel(notificationChannel).createdBy(createdBy)
                .tenancyId(tenancyId).createdAt(createdAt).lastFiredAt(lastFiredAt);
    }

    public static Builder builder(String conditionType, String targetName) {
        return new Builder(conditionType, targetName);
    }

    public static final class Builder {
        private final String conditionType;
        private final String targetName;
        private UUID id;
        private Integer thresholdSeconds;
        private Integer thresholdCount;
        private String notificationChannel;
        private String createdBy;
        private String tenancyId;
        private Instant createdAt;
        private Instant lastFiredAt;

        private Builder(String conditionType, String targetName) {
            this.conditionType = conditionType;
            this.targetName = targetName;
        }

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder thresholdSeconds(Integer v) { this.thresholdSeconds = v; return this; }
        public Builder thresholdCount(Integer v) { this.thresholdCount = v; return this; }
        public Builder notificationChannel(String v) { this.notificationChannel = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder lastFiredAt(Instant v) { this.lastFiredAt = v; return this; }

        public Watchdog build() {
            return new Watchdog(id, conditionType, targetName, thresholdSeconds,
                    thresholdCount, notificationChannel, createdBy, tenancyId,
                    createdAt, lastFiredAt);
        }
    }
}

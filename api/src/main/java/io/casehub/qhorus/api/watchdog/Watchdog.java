package io.casehub.qhorus.api.watchdog;

import java.time.Instant;
import java.util.UUID;

public record Watchdog(
        UUID id,
        WatchdogConditionType conditionType,
        String targetName,
        Integer thresholdSeconds,
        Integer thresholdCount,
        Integer similarityPct,
        String notificationChannel,
        String createdBy,
        String tenancyId,
        Instant createdAt,
        Instant lastFiredAt) {

    public Builder toBuilder() {
        return new Builder(conditionType, targetName).id(id)
                                                     .thresholdSeconds(thresholdSeconds).thresholdCount(thresholdCount)
                                                     .similarityPct(similarityPct)
                                                     .notificationChannel(notificationChannel).createdBy(createdBy)
                                                     .tenancyId(tenancyId).createdAt(createdAt).lastFiredAt(lastFiredAt);
    }

    public static Builder builder(WatchdogConditionType conditionType, String targetName) {
        return new Builder(conditionType, targetName);
    }

    public static final class Builder {
        private final WatchdogConditionType conditionType;
        private final String                targetName;
        private       UUID                  id;
        private       Integer               thresholdSeconds;
        private       Integer               thresholdCount;
        private       Integer               similarityPct;
        private       String                notificationChannel;
        private       String                createdBy;
        private       String                tenancyId;
        private       Instant               createdAt;
        private       Instant               lastFiredAt;

        private Builder(WatchdogConditionType conditionType, String targetName) {
            this.conditionType = conditionType;
            this.targetName    = targetName;
        }

        public Builder id(UUID v)                    {
                                                         this.id = v;
                                                         return this;
                                                     }

        public Builder thresholdSeconds(Integer v)   {
                                                         this.thresholdSeconds = v;
                                                         return this;
                                                     }

        public Builder thresholdCount(Integer v)     {
                                                         this.thresholdCount = v;
                                                         return this;
                                                     }

        public Builder similarityPct(Integer v)      {
                                                         this.similarityPct = v;
                                                         return this;
                                                     }

        public Builder notificationChannel(String v) {
                                                         this.notificationChannel = v;
                                                         return this;
                                                     }

        public Builder createdBy(String v)           {
                                                         this.createdBy = v;
                                                         return this;
                                                     }

        public Builder tenancyId(String v)           {
                                                         this.tenancyId = v;
                                                         return this;
                                                     }

        public Builder createdAt(Instant v)          {
                                                         this.createdAt = v;
                                                         return this;
                                                     }

        public Builder lastFiredAt(Instant v)        {
                                                         this.lastFiredAt = v;
                                                         return this;
                                                     }

        public Watchdog build() {
            return new Watchdog(id, conditionType, targetName, thresholdSeconds,
                                thresholdCount, similarityPct, notificationChannel, createdBy,
                                tenancyId, createdAt, lastFiredAt);
        }
    }
}

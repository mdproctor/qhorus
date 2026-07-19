package io.casehub.qhorus.api.channel;

import java.time.Instant;
import java.util.UUID;

public record ChannelSummary(
        UUID id,
        UUID channelId,
        String content,
        Instant updatedAt,
        String updatedBy,
        Long lastUpdatedMessageId,
        Integer updateAfterMessages,
        Integer updateAfterSeconds,
        String tenancyId) {

    public Builder toBuilder() {
        return new Builder(channelId)
                .id(id).content(content).updatedAt(updatedAt).updatedBy(updatedBy)
                .lastUpdatedMessageId(lastUpdatedMessageId)
                .updateAfterMessages(updateAfterMessages).updateAfterSeconds(updateAfterSeconds)
                .tenancyId(tenancyId);
    }

    public static Builder builder(UUID channelId) {
        return new Builder(channelId);
    }

    public static final class Builder {
        private final UUID channelId;
        private UUID id;
        private String content;
        private Instant updatedAt;
        private String updatedBy;
        private Long lastUpdatedMessageId;
        private Integer updateAfterMessages;
        private Integer updateAfterSeconds;
        private String tenancyId;

        private Builder(UUID channelId) { this.channelId = channelId; }

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder updatedBy(String v) { this.updatedBy = v; return this; }
        public Builder lastUpdatedMessageId(Long v) { this.lastUpdatedMessageId = v; return this; }
        public Builder updateAfterMessages(Integer v) { this.updateAfterMessages = v; return this; }
        public Builder updateAfterSeconds(Integer v) { this.updateAfterSeconds = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }

        public ChannelSummary build() {
            return new ChannelSummary(id, channelId, content, updatedAt, updatedBy,
                    lastUpdatedMessageId, updateAfterMessages, updateAfterSeconds, tenancyId);
        }
    }
}

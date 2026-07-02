package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.UUID;

public record Commitment(
        UUID id,
        String correlationId,
        UUID channelId,
        MessageType messageType,
        String requester,
        String obligor,
        CommitmentState state,
        Instant expiresAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        String delegatedTo,
        UUID parentCommitmentId,
        String tenancyId,
        Instant createdAt) {

    public Builder toBuilder() {
        return new Builder()
                .id(id).correlationId(correlationId).channelId(channelId)
                .messageType(messageType).requester(requester).obligor(obligor)
                .state(state).expiresAt(expiresAt).acknowledgedAt(acknowledgedAt)
                .resolvedAt(resolvedAt).delegatedTo(delegatedTo)
                .parentCommitmentId(parentCommitmentId).tenancyId(tenancyId)
                .createdAt(createdAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String correlationId;
        private UUID channelId;
        private MessageType messageType;
        private String requester;
        private String obligor;
        private CommitmentState state;
        private Instant expiresAt;
        private Instant acknowledgedAt;
        private Instant resolvedAt;
        private String delegatedTo;
        private UUID parentCommitmentId;
        private String tenancyId;
        private Instant createdAt;

        private Builder() {}

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder channelId(UUID v) { this.channelId = v; return this; }
        public Builder messageType(MessageType v) { this.messageType = v; return this; }
        public Builder requester(String v) { this.requester = v; return this; }
        public Builder obligor(String v) { this.obligor = v; return this; }
        public Builder state(CommitmentState v) { this.state = v; return this; }
        public Builder expiresAt(Instant v) { this.expiresAt = v; return this; }
        public Builder acknowledgedAt(Instant v) { this.acknowledgedAt = v; return this; }
        public Builder resolvedAt(Instant v) { this.resolvedAt = v; return this; }
        public Builder delegatedTo(String v) { this.delegatedTo = v; return this; }
        public Builder parentCommitmentId(UUID v) { this.parentCommitmentId = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public Commitment build() {
            return new Commitment(id, correlationId, channelId, messageType,
                    requester, obligor, state, expiresAt, acknowledgedAt,
                    resolvedAt, delegatedTo, parentCommitmentId, tenancyId, createdAt);
        }
    }
}

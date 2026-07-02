package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;

public record Message(
        Long id,
        UUID channelId,
        String sender,
        MessageType messageType,
        ActorType actorType,
        String tenancyId,
        String content,
        String correlationId,
        Long inReplyTo,
        int replyCount,
        List<UUID> artefactRefs,
        String target,
        UUID commitmentId,
        Instant deadline,
        Instant acknowledgedAt,
        int version,
        Instant createdAt) {

    public Message {
        artefactRefs = artefactRefs != null ? List.copyOf(artefactRefs) : null;
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id).channelId(channelId).sender(sender).messageType(messageType)
                .actorType(actorType).tenancyId(tenancyId).content(content)
                .correlationId(correlationId).inReplyTo(inReplyTo).replyCount(replyCount)
                .artefactRefs(artefactRefs).target(target).commitmentId(commitmentId)
                .deadline(deadline).acknowledgedAt(acknowledgedAt).version(version)
                .createdAt(createdAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long id;
        private UUID channelId;
        private String sender;
        private MessageType messageType;
        private ActorType actorType;
        private String tenancyId;
        private String content;
        private String correlationId;
        private Long inReplyTo;
        private int replyCount;
        private List<UUID> artefactRefs;
        private String target;
        private UUID commitmentId;
        private Instant deadline;
        private Instant acknowledgedAt;
        private int version;
        private Instant createdAt;

        private Builder() {}

        public Builder id(Long v) { this.id = v; return this; }
        public Builder channelId(UUID v) { this.channelId = v; return this; }
        public Builder sender(String v) { this.sender = v; return this; }
        public Builder messageType(MessageType v) { this.messageType = v; return this; }
        public Builder actorType(ActorType v) { this.actorType = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder inReplyTo(Long v) { this.inReplyTo = v; return this; }
        public Builder replyCount(int v) { this.replyCount = v; return this; }
        public Builder artefactRefs(List<UUID> v) { this.artefactRefs = v; return this; }
        public Builder target(String v) { this.target = v; return this; }
        public Builder commitmentId(UUID v) { this.commitmentId = v; return this; }
        public Builder deadline(Instant v) { this.deadline = v; return this; }
        public Builder acknowledgedAt(Instant v) { this.acknowledgedAt = v; return this; }
        public Builder version(int v) { this.version = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public Message build() {
            return new Message(id, channelId, sender, messageType, actorType,
                    tenancyId, content, correlationId, inReplyTo, replyCount,
                    artefactRefs, target, commitmentId, deadline, acknowledgedAt,
                    version, createdAt);
        }
    }
}

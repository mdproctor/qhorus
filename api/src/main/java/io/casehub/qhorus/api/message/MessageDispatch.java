package io.casehub.qhorus.api.message;

import java.util.UUID;
import io.casehub.platform.api.identity.ActorType;

public record MessageDispatch(
        UUID channelId,
        String sender,
        MessageType type,
        String content,
        String correlationId,
        Long inReplyTo,
        String artefactRefs,  // comma-separated UUID strings, matches Message entity storage; nullable
        String target,
        UUID subjectId,
        UUID causedByEntryId,
        ActorType actorType) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID channelId;
        private String sender;
        private MessageType type;
        private String content;
        private String correlationId;
        private Long inReplyTo;
        private String artefactRefs;
        private String target;
        private UUID subjectId;
        private UUID causedByEntryId;
        private ActorType actorType;

        public Builder channelId(UUID v)       { this.channelId = v;       return this; }
        public Builder sender(String v)         { this.sender = v;           return this; }
        public Builder type(MessageType v)      { this.type = v;             return this; }
        public Builder content(String v)        { this.content = v;          return this; }
        public Builder correlationId(String v)  { this.correlationId = v;    return this; }
        public Builder inReplyTo(Long v)        { this.inReplyTo = v;        return this; }
        public Builder artefactRefs(String v)   { this.artefactRefs = v;     return this; }
        public Builder target(String v)         { this.target = v;           return this; }
        public Builder subjectId(UUID v)        { this.subjectId = v;        return this; }
        public Builder causedByEntryId(UUID v)  { this.causedByEntryId = v;  return this; }
        public Builder actorType(ActorType v)   { this.actorType = v;        return this; }

        public MessageDispatch build() {
            if (channelId == null) throw new IllegalArgumentException("channelId is required");
            if (sender == null || sender.isBlank()) throw new IllegalArgumentException("sender is required");
            if (type == null) throw new IllegalArgumentException("type is required");
            if (actorType == null) throw new IllegalArgumentException("actorType is required");

            switch (type) {
                case DONE, DECLINE, FAILURE -> {
                    if (inReplyTo == null)
                        throw new IllegalArgumentException(type.name() + " requires inReplyTo");
                    if (correlationId == null)
                        throw new IllegalArgumentException(
                            type.name() + " requires correlationId for commitment resolution");
                }
                case RESPONSE -> {
                    if (inReplyTo == null)
                        throw new IllegalArgumentException("RESPONSE requires inReplyTo");
                    if (correlationId == null)
                        throw new IllegalArgumentException(
                            "RESPONSE requires correlationId for commitment resolution");
                }
                case HANDOFF -> {
                    if (inReplyTo == null)
                        throw new IllegalArgumentException("HANDOFF requires inReplyTo");
                    if (correlationId == null)
                        throw new IllegalArgumentException("HANDOFF requires correlationId");
                    if (target == null || target.isBlank())
                        throw new IllegalArgumentException("HANDOFF requires target");
                }
                default -> { /* COMMAND, QUERY, EVENT, STATUS — no required reply fields */ }
            }
            return new MessageDispatch(channelId, sender, type, content, correlationId,
                    inReplyTo, artefactRefs, target, subjectId, causedByEntryId, actorType);
        }
    }
}

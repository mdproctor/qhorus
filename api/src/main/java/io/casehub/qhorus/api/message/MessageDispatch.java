package io.casehub.qhorus.api.message;

import io.casehub.platform.api.identity.ActorType;

import java.time.Instant;
import java.util.UUID;

public record MessageDispatch(
        UUID channelId,
        String sender,
        MessageType type,
        String content,
        String payload,
        String correlationId,
        Long inReplyTo,
        java.util.List<ArtefactRef> artefactRefs,
        String target,
        UUID subjectId,
        UUID causedByEntryId,
        ActorType actorType,
        Instant deadline,
        String telemetry,
        String tenancyId,
        String topic,
        Long correctsMessageId,
        boolean retraction) {

    public static Builder builder() {return new Builder();}

    public MessageDispatch withTarget(String newTarget) {
        return new MessageDispatch(channelId, sender, type, content, payload, correlationId,
                                   inReplyTo, artefactRefs, newTarget, subjectId, causedByEntryId, actorType,
                                   deadline, telemetry, tenancyId, topic, correctsMessageId, retraction);
    }


    public static final class Builder {
        private UUID                        channelId;
        private String                      sender;
        private MessageType                 type;
        private String                      content;
        private String                      payload;
        private String                      correlationId;
        private Long                        inReplyTo;
        private java.util.List<ArtefactRef> artefactRefs;
        private String                      target;
        private UUID                        subjectId;
        private UUID                        causedByEntryId;
        private ActorType                   actorType;
        private Instant                     deadline;
        private String                      telemetry;
        private String                      tenancyId;
        private String                      topic;
        private Long                        correctsMessageId;
        private boolean                     retraction;

        public Builder channelId(UUID v) {
            this.channelId = v;
            return this;
        }

        public Builder sender(String v) {
            this.sender = v;
            return this;
        }

        public Builder type(MessageType v) {
            this.type = v;
            return this;
        }

        public Builder content(String v) {
            this.content = v;
            return this;
        }

        public Builder payload(String v) {
            this.payload = v;
            return this;
        }

        public Builder correlationId(String v) {
            this.correlationId = v;
            return this;
        }

        public Builder inReplyTo(Long v) {
            this.inReplyTo = v;
            return this;
        }

        public Builder artefactRefs(java.util.List<ArtefactRef> v) {
            this.artefactRefs = v;
            return this;
        }

        public Builder target(String v) {
            this.target = v;
            return this;
        }

        public Builder subjectId(UUID v) {
            this.subjectId = v;
            return this;
        }

        public Builder causedByEntryId(UUID v) {
            this.causedByEntryId = v;
            return this;
        }

        public Builder actorType(ActorType v) {
            this.actorType = v;
            return this;
        }

        public Builder deadline(Instant v) {
            this.deadline = v;
            return this;
        }

        public Builder telemetry(String v) {
            this.telemetry = v;
            return this;
        }

        public Builder tenancyId(String v) {
            this.tenancyId = v;
            return this;
        }

        public Builder topic(String v) {
            this.topic = v;
            return this;
        }

        public Builder correctsMessageId(Long v) {
            this.correctsMessageId = v;
            return this;
        }

        public Builder retraction(boolean v) {
            this.retraction = v;
            return this;
        }

        public MessageDispatch build() {
            if (channelId == null) {throw new IllegalArgumentException("channelId is required");}
            if (sender == null || sender.isBlank()) {throw new IllegalArgumentException("sender is required");}
            if (type == null) {throw new IllegalArgumentException("type is required");}
            if (actorType == null) {throw new IllegalArgumentException("actorType is required");}

            switch (type) {
                case DONE, DECLINE, FAILURE -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException(type.name() + " requires inReplyTo");}
                    if (correlationId == null) {
                        throw new IllegalArgumentException(type.name() + " requires correlationId for commitment resolution");
                    }
                }
                case RESPONSE -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException("RESPONSE requires inReplyTo");}
                    if (correlationId == null) {
                        throw new IllegalArgumentException("RESPONSE requires correlationId for commitment resolution");
                    }
                }
                case HANDOFF -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException("HANDOFF requires inReplyTo");}
                    if (correlationId == null) {throw new IllegalArgumentException("HANDOFF requires correlationId");}
                    if (target == null || target.isBlank()) {
                        throw new IllegalArgumentException("HANDOFF requires target");
                    }
                }
                case PROPOSE -> {
                    if (correlationId == null) {
                        throw new IllegalArgumentException("PROPOSE requires correlationId for commitment tracking");
                    }
                    if (content == null || content.isBlank()) {
                        throw new IllegalArgumentException("PROPOSE requires content (proposal terms)");
                    }
                }
                case EVENT -> {
                    if (content != null) {
                        throw new IllegalArgumentException("EVENT messages must not carry content — use STATUS for content-bearing observe-channel broadcasts.");
                    }
                }
                default -> {}
            }

            if (retraction && correctsMessageId == null) {
                throw new IllegalArgumentException("retraction requires correctsMessageId");
            }

            if (topic == null || topic.isBlank()) {
                topic = "general";
            } else {
                topic = topic.strip();
                if (topic.length() > 200) {
                    throw new IllegalArgumentException("topic exceeds 200 characters");
                }
            }

            return new MessageDispatch(channelId, sender, type, content, payload, correlationId,
                                       inReplyTo, artefactRefs, target, subjectId, causedByEntryId, actorType, deadline, telemetry,
                                       tenancyId, topic, correctsMessageId, retraction);
        }
    }
}

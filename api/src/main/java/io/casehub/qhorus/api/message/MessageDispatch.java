package io.casehub.qhorus.api.message;

import io.casehub.platform.api.identity.ActorType;

import java.time.Instant;
import java.util.UUID;

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
        ActorType actorType,
        Instant deadline,
        /** Internal: EVENT-only ledger telemetry payload; parsed by LedgerWriteService; never delivered to observers. */
        String telemetry,
        /** Tenant scope for this dispatch. Null = auto-resolved by MessageService from CurrentPrincipal. */
        String tenancyId,
        /** Topic for this message. Null/blank defaults to "general". */
        String topic) {

    public static Builder builder() {return new Builder();}

    public static final class Builder {
        private UUID        channelId;
        private String      sender;
        private MessageType type;
        private String      content;
        private String      correlationId;
        private Long        inReplyTo;
        private String      artefactRefs;
        private String      target;
        private UUID        subjectId;
        private UUID        causedByEntryId;
        private ActorType   actorType;
        private Instant     deadline;
        private String      telemetry;
        private String      tenancyId;
        private String      topic;

        public Builder channelId(UUID v)       {
                                                   this.channelId = v;
                                                   return this;
                                               }

        public Builder sender(String v)        {
                                                   this.sender = v;
                                                   return this;
                                               }

        public Builder type(MessageType v)     {
                                                   this.type = v;
                                                   return this;
                                               }

        public Builder content(String v)       {
                                                   this.content = v;
                                                   return this;
                                               }

        public Builder correlationId(String v) {
                                                   this.correlationId = v;
                                                   return this;
                                               }

        public Builder inReplyTo(Long v)       {
                                                   this.inReplyTo = v;
                                                   return this;
                                               }

        public Builder artefactRefs(String v)  {
                                                   this.artefactRefs = v;
                                                   return this;
                                               }

        public Builder target(String v)        {
                                                   this.target = v;
                                                   return this;
                                               }

        public Builder subjectId(UUID v)       {
                                                   this.subjectId = v;
                                                   return this;
                                               }

        public Builder causedByEntryId(UUID v) {
                                                   this.causedByEntryId = v;
                                                   return this;
                                               }

        public Builder actorType(ActorType v)  {
                                                   this.actorType = v;
                                                   return this;
                                               }

        public Builder deadline(Instant v)     {
                                                   this.deadline = v;
                                                   return this;
                                               }

        public Builder telemetry(String v)     {
                                                   this.telemetry = v;
                                                   return this;
                                               }

        public Builder tenancyId(String v)     {
                                                   this.tenancyId = v;
                                                   return this;
                                               }

        public Builder topic(String v)         {
                                                   this.topic = v;
                                                   return this;
                                               }

        /**
         * Validates and builds the dispatch. Enforcement matrix:
         * <ul>
         *   <li>DONE, DECLINE, FAILURE — require both {@code inReplyTo} AND {@code correlationId};
         *       they resolve a COMMAND commitment via the correlationId thread</li>
         *   <li>RESPONSE — requires both {@code inReplyTo} AND {@code correlationId};
         *       fulfills a QUERY commitment. The correlationId requirement is identical to
         *       DONE/DECLINE/FAILURE — both are needed for commitment resolution and causal chain
         *       integrity, regardless of whether the original message was a COMMAND or QUERY.</li>
         *   <li>HANDOFF — requires {@code inReplyTo}, {@code correlationId}, AND {@code target}</li>
         *   <li>EVENT — must not carry {@code content}; use STATUS for content-bearing observe-channel
         *       broadcasts. Use {@code telemetry} for internal ledger telemetry payloads.</li>
         *   <li>COMMAND, QUERY, STATUS — no required reply fields</li>
         * </ul>
         */
        public MessageDispatch build() {
            if (channelId == null) {throw new IllegalArgumentException("channelId is required");}
            if (sender == null || sender.isBlank()) {throw new IllegalArgumentException("sender is required");}
            if (type == null) {throw new IllegalArgumentException("type is required");}
            if (actorType == null) {throw new IllegalArgumentException("actorType is required");}

            switch (type) {
                case DONE, DECLINE, FAILURE -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException(type.name() + " requires inReplyTo");}
                    if (correlationId == null) {
                        throw new IllegalArgumentException(
                                type.name() + " requires correlationId for commitment resolution");
                    }
                }
                case RESPONSE -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException("RESPONSE requires inReplyTo");}
                    if (correlationId == null) {
                        throw new IllegalArgumentException(
                                "RESPONSE requires correlationId for commitment resolution");
                    }
                }
                case HANDOFF -> {
                    if (inReplyTo == null) {throw new IllegalArgumentException("HANDOFF requires inReplyTo");}
                    if (correlationId == null) {throw new IllegalArgumentException("HANDOFF requires correlationId");}
                    if (target == null || target.isBlank()) {
                        throw new IllegalArgumentException("HANDOFF requires target");
                    }
                }
                case EVENT -> {
                    if (content != null) {
                        throw new IllegalArgumentException(
                                "EVENT messages must not carry content — use STATUS for content-bearing observe-channel broadcasts.");
                    }
                }
                default -> { /* COMMAND, QUERY, STATUS — no required reply fields */ }
            }

            // Topic: null/blank defaults to "general"; validated for length
            if (topic == null || topic.isBlank()) {
                topic = "general";
            } else {
                topic = topic.strip();
                if (topic.length() > 200) {
                    throw new IllegalArgumentException("topic exceeds 200 characters");
                }
            }

            return new MessageDispatch(channelId, sender, type, content, correlationId,
                                       inReplyTo, artefactRefs, target, subjectId, causedByEntryId, actorType, deadline, telemetry,
                                       tenancyId, topic);
        }
    }
}

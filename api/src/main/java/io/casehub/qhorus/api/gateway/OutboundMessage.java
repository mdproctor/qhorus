package io.casehub.qhorus.api.gateway;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;

import java.util.UUID;

public record OutboundMessage(
        UUID messageId,
        Long sequenceId,
        String sender,
        MessageType type,
        String content,
        String payload,
        String correlationId,
        Long inReplyTo,
        ActorType senderActorType,
        java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
        String target,
        String topic,
        Long correctsMessageId,
        boolean retraction) {

    public OutboundMessage(UUID messageId, Long sequenceId, String sender, MessageType type,
                           String content, String payload, String correlationId, Long inReplyTo,
                           ActorType senderActorType,
                           java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                           String target, String topic) {
        this(messageId, sequenceId, sender, type, content, payload, correlationId, inReplyTo,
             senderActorType, artefactRefs, target, topic, null, false);
    }

    public OutboundMessage(UUID messageId, String sender, MessageType type, String content,
                           String correlationId, Long inReplyTo, ActorType senderActorType,
                           java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                           String target, String topic) {
        this(messageId, null, sender, type, content, null, correlationId, inReplyTo,
             senderActorType, artefactRefs, target, topic, null, false);
    }

    public OutboundMessage(UUID messageId, String sender, MessageType type, String content,
                           String correlationId, Long inReplyTo, ActorType senderActorType,
                           java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                           String target) {
        this(messageId, null, sender, type, content, null, correlationId, inReplyTo,
             senderActorType, artefactRefs, target, null, null, false);
    }
}

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
        String correlationId,
        Long inReplyTo,
        ActorType senderActorType,
        java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
        String target) {

    public OutboundMessage(UUID messageId, String sender, MessageType type, String content,
                           String correlationId, Long inReplyTo, ActorType senderActorType,
                           java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                           String target) {
        this(messageId, null, sender, type, content, correlationId, inReplyTo,
             senderActorType, artefactRefs, target);
    }
}

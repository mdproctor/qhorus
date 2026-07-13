package io.casehub.qhorus.api.message;

import io.casehub.platform.api.identity.ActorType;

import java.time.Instant;
import java.util.UUID;

public record MessageView(
        Long id,
        UUID channelId,
        String sender,
        MessageType type,
        String content,
        String correlationId,
        Long inReplyTo,
        String target,
        String topic,
        java.util.List<ArtefactRef> artefactRefs,
        ActorType actorType,
        Instant createdAt,
        Instant deadline,
        int replyCount) {}

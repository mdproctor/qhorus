package io.casehub.qhorus.api.gateway;

import java.util.UUID;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.message.MessageType;

public record OutboundMessage(
        UUID messageId,
        String sender,
        MessageType type,
        String content,
        UUID correlationId,
        ActorType senderActorType) {}

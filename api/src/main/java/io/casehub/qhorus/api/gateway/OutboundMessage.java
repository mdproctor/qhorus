package io.casehub.qhorus.api.gateway;

import java.util.UUID;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.message.MessageType;

/**
 * @param correlationId null for EVENT messages not correlated to a COMMAND; non-null for
 *                      RESPONSE, DONE, FAILURE, DECLINE, STATUS linked to a prior COMMAND
 */
public record OutboundMessage(
        UUID messageId,
        String sender,
        MessageType type,
        String content,
        UUID correlationId,
        ActorType senderActorType) {}

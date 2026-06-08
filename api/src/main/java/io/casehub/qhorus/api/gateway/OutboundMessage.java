package io.casehub.qhorus.api.gateway;

import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;

/**
 * @param messageId     delivery-scoped identifier for this fan-out event; NOT the Qhorus
 *                      ledger message ID (which is a Long). Backends must not use this to
 *                      reference messages in the Qhorus ledger.
 * @param correlationId null for EVENT messages not correlated to a COMMAND; non-null for
 *                      RESPONSE, DONE, FAILURE, DECLINE, STATUS linked to a prior COMMAND.
 *                      UUID form — converted from the String correlationId in
 *                      {@link io.casehub.qhorus.api.message.MessageDispatch} by the fanOut caller
 * @param inReplyTo     the Qhorus ledger message ID (Long) of the message being replied to.
 *                      Required (non-null) when type is DONE, DECLINE, FAILURE, RESPONSE, or
 *                      HANDOFF — these types require {@code inReplyTo} in the
 *                      {@link io.casehub.qhorus.api.message.MessageDispatch} builder.
 *                      Null for COMMAND, QUERY, STATUS, and EVENT.
 * @param content       {@code null} for EVENT — backends must not rely on content being non-null
 *                      for EVENT messages.
 */
public record OutboundMessage(
        UUID messageId,
        String sender,
        MessageType type,
        String content,
        UUID correlationId,
        Long inReplyTo,
        ActorType senderActorType) {}

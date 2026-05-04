package io.casehub.qhorus.api.gateway;

import io.casehub.qhorus.api.message.MessageType;

/**
 * senderInstanceId MUST use format "human:{externalSenderId}" so
 * ActorTypeResolver correctly stamps ActorType.HUMAN in the ledger.
 */
public record NormalisedMessage(
        MessageType type,
        String content,
        String senderInstanceId) {}

package io.casehub.qhorus.api.spi;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;

import java.util.List;
import java.util.UUID;

public record ProtocolContext(
        UUID channelId,
        String channelName,
        MessageType incomingType,
        String sender,
        String correlationId,
        List<String> protocolParticipants,
        List<MessageView> recentMessages,
        List<Commitment> activeCommitments) {

    public ProtocolContext {
        protocolParticipants = protocolParticipants != null ? List.copyOf(protocolParticipants) : List.of();
        recentMessages = recentMessages != null ? List.copyOf(recentMessages) : List.of();
        activeCommitments = activeCommitments != null ? List.copyOf(activeCommitments) : List.of();
    }
}

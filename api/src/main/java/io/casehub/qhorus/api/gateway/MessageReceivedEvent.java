package io.casehub.qhorus.api.gateway;

import io.casehub.qhorus.api.message.MessageType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageReceivedEvent(
        String channelName,
        UUID channelId,
        String tenancyId,
        MessageType messageType,
        String senderId,
        String correlationId,
        Instant occurredAt,
        String content,
        String topic) {

    public MessageReceivedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (messageType == MessageType.EVENT && content != null) {
            throw new IllegalArgumentException(
                    "EVENT messages must have null content — Builder.build() enforces this at call-site");
        }
    }
}

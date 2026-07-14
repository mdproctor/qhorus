package io.casehub.qhorus.api.gateway;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageReceivedEvent(
        Long messageId,
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

    public static MessageReceivedEvent fromMessage(Message message, String channelName) {
        String  content    = message.messageType() == MessageType.EVENT ? null : message.content();
        Instant occurredAt = message.createdAt() != null ? message.createdAt() : Instant.now();
        return new MessageReceivedEvent(
                message.id(), channelName, message.channelId(), message.tenancyId(),
                message.messageType(), message.sender(), message.correlationId(),
                occurredAt, content, message.topic());
    }
}

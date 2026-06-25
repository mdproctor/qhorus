package io.casehub.qhorus.api.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Fired by {@link MessageObserver} implementations after every message persisted
 * to a qhorus channel — all 9 speech-act types.
 *
 * <p>{@code content} is always {@code null} for {@link MessageType#EVENT}
 * because the Builder guard ({@link io.casehub.qhorus.api.message.MessageDispatch.Builder#build()})
 * prevents non-null content from reaching the dispatcher.
 * Once {@code casehub-ledger#126} fully decouples telemetry capture from content,
 * the architectural decision on whether EVENT should support application content
 * can be made explicitly at that time.
 */
public record MessageReceivedEvent(
        String channelName,
        UUID channelId,
        String tenancyId,
        MessageType messageType,
        String senderId,
        String correlationId,
        Instant occurredAt,
        String content) {

    public MessageReceivedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (messageType == MessageType.EVENT && content != null) {
            throw new IllegalArgumentException(
                    "EVENT messages must have null content — Builder.build() enforces this at call-site");
        }
    }
}

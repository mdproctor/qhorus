package io.casehub.qhorus.runtime.gateway;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.message.MessageType;

@DefaultBean
@ApplicationScoped
public class DefaultInboundNormaliser implements InboundNormaliser {

    @Override
    public NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw) {
        return new NormalisedMessage(
                parseType(raw.metadata().get("message-type")),
                raw.content(),
                "human:" + raw.externalSenderId(),
                raw.correlationId(),
                raw.inReplyTo(),
                null,
                null);
    }

    private static MessageType parseType(String value) {
        if (value == null || value.isBlank()) return MessageType.QUERY;
        try {
            MessageType type = MessageType.valueOf(value.toUpperCase());
            // HANDOFF requires a target field that InboundHumanMessage does not carry.
            // Backends needing HANDOFF must provide a custom normaliser() implementation.
            if (type == MessageType.HANDOFF) return MessageType.QUERY;
            return type;
        } catch (IllegalArgumentException e) {
            return MessageType.QUERY;
        }
    }
}

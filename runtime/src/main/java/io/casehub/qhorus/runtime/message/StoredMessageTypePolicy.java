package io.casehub.qhorus.runtime.message;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.Channel;

@ApplicationScoped
public class StoredMessageTypePolicy implements MessageTypePolicy {

    @Override
    public void validate(Channel channel, MessageType type) {
        // Denial-first: denial wins over allowedTypes.
        // Unreachable tie-break in practice — ChannelCreateRequest compact constructor
        // rejects any channel where allowedTypes ∩ deniedTypes is non-empty.
        if (channel.deniedTypes != null && !channel.deniedTypes.isBlank()) {
            if (MessageType.parseTypes(channel.deniedTypes).contains(type)) {
                throw MessageTypeViolationException.denied(channel.name, type, channel.deniedTypes);
            }
        }
        // Open channel (no allowedTypes restriction) passes after denial check
        if (channel.allowedTypes == null || channel.allowedTypes.isBlank()) {
            return;
        }
        if (!MessageType.parseTypes(channel.allowedTypes).contains(type)) {
            throw new MessageTypeViolationException(channel.name, type, channel.allowedTypes);
        }
    }
}

package io.casehub.qhorus.runtime.message;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;

@ApplicationScoped
public class StoredMessageTypePolicy implements MessageTypePolicy {

    @Override
    public void validate(Channel channel, MessageType type) {
        if (type != MessageType.COMMAND && type != MessageType.QUERY) return;
        if (channel.deniedTypes() != null && channel.deniedTypes().contains(type)) {
            throw MessageTypeViolationException.denied(channel.name(), type,
                    MessageType.serializeTypes(channel.deniedTypes()));
        }
        if (channel.allowedTypes() == null) return;
        if (!channel.allowedTypes().contains(type)) {
            throw new MessageTypeViolationException(channel.name(), type,
                    MessageType.serializeTypes(channel.allowedTypes()));
        }
    }

    @Override
    public String advisory(Channel channel, MessageType type) {
        if (type == MessageType.COMMAND || type == MessageType.QUERY) return null;
        if (channel.deniedTypes() != null && channel.deniedTypes().contains(type)) {
            return "Type advisory: channel '" + channel.name()
                    + "' explicitly denies " + type
                    + " — denied: " + MessageType.serializeTypes(channel.deniedTypes()) + ". Message dispatched.";
        }
        if (channel.allowedTypes() == null) return null;
        if (!channel.allowedTypes().contains(type)) {
            return "Type advisory: channel '" + channel.name()
                    + "' allows " + MessageType.serializeTypes(channel.allowedTypes()) + " only, received " + type
                    + ". Message dispatched.";
        }
        return null;
    }
}

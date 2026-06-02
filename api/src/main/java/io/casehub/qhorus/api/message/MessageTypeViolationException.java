package io.casehub.qhorus.api.message;

public class MessageTypeViolationException extends IllegalStateException {

    public MessageTypeViolationException(String channel, MessageType attempted, String allowed) {
        super("Channel '" + channel + "' does not permit " + attempted + ". Allowed: " + allowed);
    }

    private MessageTypeViolationException(String message) {
        super(message);
    }

    public static MessageTypeViolationException denied(String channel, MessageType attempted, String denied) {
        return new MessageTypeViolationException(
                "Channel '" + channel + "' explicitly denies " + attempted + ". Denied: " + denied);
    }
}

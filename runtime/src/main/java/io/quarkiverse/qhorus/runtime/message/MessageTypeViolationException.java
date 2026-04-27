package io.quarkiverse.qhorus.runtime.message;

public class MessageTypeViolationException extends RuntimeException {

    public MessageTypeViolationException(String channel, MessageType attempted, String allowed) {
        super("Channel '" + channel + "' does not permit " + attempted + ". Allowed: " + allowed);
    }
}

package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.api.channel.Channel;

@FunctionalInterface
public interface MessageTypePolicy {

    /**
     * Hard-block gate. Throw {@link MessageTypeViolationException} to reject; return normally
     * to permit. {@link StoredMessageTypePolicy} hard-enforces only COMMAND and QUERY
     * violations — these are the only types that call {@code commitmentService.open()};
     * advisory dispatch on the wrong channel creates orphan Commitments when the LLM corrects.
     *
     * <p>Custom policies that need hard enforcement for additional types override this method.
     */
    void validate(Channel channel, MessageType type);

    /**
     * Advisory evaluation. Returns a human-readable violation description when the type
     * violates the channel's declared constraints and the type is not COMMAND or QUERY
     * (those are hard-enforced by {@link #validate}). Returns {@code null} when permitted
     * or when the type is obligation-creating.
     *
     * <p>Never throws for well-formed channel configurations. For malformed
     * {@code allowedTypes}/{@code deniedTypes} values (unknown type names), propagates
     * {@link IllegalArgumentException} from {@link MessageType#parseTypes} — an impossible
     * condition in production since {@code ChannelCreateRequest} validates at creation time.
     *
     * <p><strong>Calling contract for custom implementations:</strong> when a custom policy
     * provides only {@code validate()} and leaves {@code advisory()} as the default null,
     * the calling sequence is: {@code validate()} → may throw →
     * {@code advisory()} → null (no advisory logged, because advisory() is called only
     * after validate() returns normally; if validate() throws, advisory() is never invoked).
     * This is the correct hard-enforcement-only mode.
     *
     * <p>Default: {@code null} — no advisory; defers entirely to {@code validate()}.
     */
    default String advisory(Channel channel, MessageType type) {
        return null;
    }
}

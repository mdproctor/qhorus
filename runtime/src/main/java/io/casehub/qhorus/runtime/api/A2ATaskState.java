package io.casehub.qhorus.runtime.api;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

class A2ATaskState {

    /** Message types that terminate an A2A task stream. */
    static final Set<MessageType> TERMINAL_TYPES =
            Set.of(MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);

    /** A2A state strings that represent a terminal task outcome. */
    static final Set<String> TERMINAL_STATES = Set.of("completed", "failed", "cancelled");

    static String fromCommitmentState(final CommitmentState state) {
        return switch (state) {
            case FULFILLED -> "completed";
            case DELEGATED, ACKNOWLEDGED -> "working";
            case FAILED, EXPIRED -> "failed";
            case DECLINED -> "cancelled"; // explicit refusal — not an infrastructure failure. Refs #147.
            case OPEN -> "submitted";
        };
    }

    /**
     * Maps a single {@link MessageType} to an A2A task state string.
     * Used by SSE streaming to produce per-event state from the outbound message type.
     *
     * <p>DECLINE → "cancelled" (explicit refusal), FAILURE → "failed" (infrastructure),
     * DONE → "completed", anything else → "working" (progress update).
     */
    static String fromMessageType(final MessageType type) {
        return switch (type) {
            case DONE    -> "completed";
            case FAILURE -> "failed";
            case DECLINE -> "cancelled";
            default      -> "working";
        };
    }

    /**
     * Determines A2A task state from message history using max-priority resolution.
     * Avoids last-message-wins ordering assumption: a QUERY arriving after DONE
     * does not regress the state to "submitted".
     *
     * <p>Priority: DONE/RESPONSE=4 (completed) > FAILURE=3 (failed) > DECLINE=2 (cancelled)
     * > STATUS/HANDOFF=1 (working) > anything else=0 (submitted).
     *
     * <p>FAILURE-after-DONE deliberately returns "completed" — terminal commitment
     * states do not regress, consistent with CommitmentService state machine.
     * FAILURE+DECLINE together returns "failed" (FAILURE priority=3 > DECLINE priority=2).
     */
    static String fromMessageHistory(final List<Message> messages) {
        if (messages.isEmpty()) return "submitted";
        return messages.stream()
                .map(m -> statePriority(m.messageType))
                .max(Comparator.naturalOrder())
                .map(A2ATaskState::fromPriority)
                .orElse("submitted");
    }

    private static int statePriority(final MessageType t) {
        return switch (t) {
            case DONE, RESPONSE -> 4;
            case FAILURE        -> 3;
            case DECLINE        -> 2;
            case STATUS, HANDOFF -> 1;
            default             -> 0;
        };
    }

    private static String fromPriority(final int p) {
        return switch (p) {
            case 4 -> "completed";
            case 3 -> "failed";
            case 2 -> "cancelled";
            case 1 -> "working";
            default -> "submitted";
        };
    }
}

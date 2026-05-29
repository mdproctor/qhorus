package io.casehub.qhorus.runtime.api;

import java.util.Comparator;
import java.util.List;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

class A2ATaskState {

    static String fromCommitmentState(CommitmentState state) {
        return switch (state) {
            case FULFILLED -> "completed";
            case DELEGATED, ACKNOWLEDGED -> "working"; // DELEGATED: retained for correctness if store behaviour changes
            case FAILED, DECLINED, EXPIRED -> "failed";
            case OPEN -> "submitted";
        };
    }

    /**
     * Determines A2A task state from message history using max-priority resolution.
     * Avoids last-message-wins ordering assumption: a QUERY arriving after DONE
     * does not regress the state to "submitted".
     *
     * <p>Priority: DONE/RESPONSE=3 (completed) > FAILURE/DECLINE=2 (failed) >
     * STATUS/HANDOFF=1 (working) > anything else=0 (submitted).
     *
     * <p>FAILURE-after-DONE deliberately returns "completed" — terminal commitment
     * states do not regress, consistent with CommitmentService state machine.
     */
    static String fromMessageHistory(List<Message> messages) {
        if (messages.isEmpty()) return "submitted";
        return messages.stream()
                .map(m -> statePriority(m.messageType))
                .max(Comparator.naturalOrder())
                .map(A2ATaskState::fromPriority)
                .orElse("submitted");
    }

    private static int statePriority(MessageType t) {
        return switch (t) {
            case DONE, RESPONSE -> 3;
            case FAILURE, DECLINE -> 2;
            case STATUS, HANDOFF -> 1;
            default -> 0;
        };
    }

    private static String fromPriority(int p) {
        return switch (p) {
            case 3 -> "completed";
            case 2 -> "failed";
            case 1 -> "working";
            default -> "submitted";
        };
    }
}

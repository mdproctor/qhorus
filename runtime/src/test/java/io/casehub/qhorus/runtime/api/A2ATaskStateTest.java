package io.casehub.qhorus.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

class A2ATaskStateTest {

    // -----------------------------------------------------------------------
    // fromCommitmentState
    // -----------------------------------------------------------------------

    @Test
    void openIsSubmitted() {
        assertEquals("submitted", A2ATaskState.fromCommitmentState(CommitmentState.OPEN));
    }

    @Test
    void acknowledgedIsWorking() {
        assertEquals("working", A2ATaskState.fromCommitmentState(CommitmentState.ACKNOWLEDGED));
    }

    @Test
    void fulfilledIsCompleted() {
        assertEquals("completed", A2ATaskState.fromCommitmentState(CommitmentState.FULFILLED));
    }

    @Test
    void delegatedIsWorking() {
        assertEquals("working", A2ATaskState.fromCommitmentState(CommitmentState.DELEGATED));
    }

    @Test
    void declinedIsFailed() {
        assertEquals("failed", A2ATaskState.fromCommitmentState(CommitmentState.DECLINED));
    }

    @Test
    void failedIsFailed() {
        assertEquals("failed", A2ATaskState.fromCommitmentState(CommitmentState.FAILED));
    }

    @Test
    void expiredIsFailed() {
        assertEquals("failed", A2ATaskState.fromCommitmentState(CommitmentState.EXPIRED));
    }

    // -----------------------------------------------------------------------
    // fromMessageHistory
    // -----------------------------------------------------------------------

    @Test
    void emptyHistoryIsSubmitted() {
        assertEquals("submitted", A2ATaskState.fromMessageHistory(List.of()));
    }

    @Test
    void lastQueryIsSubmitted() {
        assertEquals("submitted", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.QUERY))));
    }

    @Test
    void lastCommandIsSubmitted() {
        assertEquals("submitted", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.COMMAND))));
    }

    @Test
    void lastEventIsSubmitted() {
        // telemetry event — does not advance task state
        assertEquals("submitted", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.EVENT))));
    }

    @Test
    void lastStatusIsWorking() {
        assertEquals("working", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.STATUS))));
    }

    @Test
    void lastHandoffIsWorking() {
        assertEquals("working", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.HANDOFF))));
    }

    @Test
    void lastResponseIsCompleted() {
        assertEquals("completed", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.RESPONSE))));
    }

    @Test
    void lastDoneIsCompleted() {
        assertEquals("completed", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.DONE))));
    }

    @Test
    void lastFailureIsFailed() {
        assertEquals("failed", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.FAILURE))));
    }

    @Test
    void lastDeclineIsFailed() {
        assertEquals("failed", A2ATaskState.fromMessageHistory(List.of(msg(MessageType.DECLINE))));
    }

    @Test
    void maxPriority_orderDoesNotMatter_maxPriorityWins() {
        // Max-priority wins regardless of message order: STATUS (priority 1) beats QUERY (priority 0)
        assertEquals("working", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.QUERY), msg(MessageType.STATUS))));
    }

    // -----------------------------------------------------------------------
    // Max-priority resolution (prevent state regression)
    // -----------------------------------------------------------------------

    @Test
    void maxPriority_doneBeforeQuery_returnsCompleted() {
        // Regression test: DONE (priority 3) should win even if QUERY comes after
        assertEquals("completed", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.DONE), msg(MessageType.QUERY))));
    }

    @Test
    void maxPriority_responseBeforeCommand_returnsCompleted() {
        // RESPONSE (priority 3) before COMMAND (priority 0)
        assertEquals("completed", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.RESPONSE), msg(MessageType.COMMAND))));
    }

    @Test
    void maxPriority_failureBeforeQuery_returnsFailed() {
        // FAILURE (priority 2) beats QUERY (priority 0)
        assertEquals("failed", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.FAILURE), msg(MessageType.QUERY))));
    }

    @Test
    void maxPriority_declineBeforeStatus_returnsFailed() {
        // DECLINE (priority 2) beats STATUS (priority 1)
        assertEquals("failed", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.DECLINE), msg(MessageType.STATUS))));
    }

    @Test
    void maxPriority_statusBeforeQuery_returnsWorking() {
        // STATUS (priority 1) beats QUERY (priority 0)
        assertEquals("working", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.STATUS), msg(MessageType.QUERY))));
    }

    @Test
    void maxPriority_handoffBeforeCommand_returnsWorking() {
        // HANDOFF (priority 1) beats COMMAND (priority 0)
        assertEquals("working", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.HANDOFF), msg(MessageType.COMMAND))));
    }

    @Test
    void maxPriority_noRegression_terminalStatesWin() {
        // Terminal state DONE (priority 3) wins over earlier FAILURE (priority 2)
        assertEquals("completed", A2ATaskState.fromMessageHistory(
                List.of(msg(MessageType.FAILURE), msg(MessageType.DONE))));
    }

    @Test
    void maxPriority_multipleHighPriority_returnsHighestPriority() {
        // Multiple messages: QUERY, STATUS, FAILURE, RESPONSE — RESPONSE (3) wins
        assertEquals("completed", A2ATaskState.fromMessageHistory(
                List.of(
                        msg(MessageType.QUERY),
                        msg(MessageType.STATUS),
                        msg(MessageType.FAILURE),
                        msg(MessageType.RESPONSE))));
    }

    private static Message msg(MessageType type) {
        Message m = new Message();
        m.messageType = type;
        return m;
    }
}

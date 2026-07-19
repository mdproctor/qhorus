package io.casehub.qhorus.api.watchdog;

public enum WatchdogConditionType {
    BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH,
    CONTEXT_PRESSURE,
    LOOP_DETECTED, OBLIGATION_FAN_OUT, CONVERSATION_STALL, ECHO_CHAMBER,
    CIRCULAR_DELEGATION;

    public static java.util.Optional<WatchdogConditionType> fromString(String value) {
        try {
            return java.util.Optional.of(valueOf(value));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}

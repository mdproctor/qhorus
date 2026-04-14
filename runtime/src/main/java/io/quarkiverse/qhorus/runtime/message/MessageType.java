package io.quarkiverse.qhorus.runtime.message;

public enum MessageType {
    /** Expects a response; carries correlation_id. */
    REQUEST,
    /** Reply to a request; carries correlation_id. */
    RESPONSE,
    /** Progress update; no reply expected. */
    STATUS,
    /** Terminal for a turn; carries target and artefact_refs. */
    HANDOFF,
    /** Signals no further replies from this agent. */
    DONE,
    /** Observer-only telemetry — NOT delivered to agent context. */
    EVENT;

    /** Whether this message type appears in agent context (true for all except EVENT). */
    public boolean isAgentVisible() {
        return this != EVENT;
    }
}

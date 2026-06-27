package io.casehub.qhorus.api.message;

/** Obligation lifecycle state for a QUERY or COMMAND commitment. */
public enum CommitmentState {

    /** QUERY or COMMAND sent; debtor must respond or decline. */
    OPEN,

    /** STATUS received; debtor is working and has extended their deadline. */
    ACKNOWLEDGED,

    /** RESPONSE (for QUERY) or DONE (for COMMAND) received; obligation discharged. */
    FULFILLED,

    /** DECLINE received; debtor refused the obligation. */
    DECLINED,

    /** FAILURE received; debtor attempted but could not complete. */
    FAILED,

    /**
     * HANDOFF received; obligation transferred to a new debtor. A child Commitment was created.
     *
     * <p><strong>Terminal state.</strong> This commitment is closed. The active obligation lives
     * in the child Commitment (state {@code OPEN}), not here.
     * Use {@code CommitmentStore.findByCorrelationId()} to locate the child — it returns the
     * child OPEN commitment after a HANDOFF, not this DELEGATED parent.
     *
     * <p><strong>Cross-system warning:</strong> {@code WorkItemStatus.DELEGATED} in
     * {@code casehub-work} (refs casehubio/work#240) is <em>non-terminal</em> —
     * it represents a pre-acceptance hold, not a closed obligation.
     * Do not conflate the two when writing integration code that crosses both systems.
     */
    DELEGATED,

    /** Deadline exceeded with no response; infrastructure-generated terminal state. */
    EXPIRED;

    /** True for all states from which no further transition is possible. */
    public boolean isTerminal() {
        return this == FULFILLED || this == DECLINED || this == FAILED
                || this == DELEGATED || this == EXPIRED;
    }

    /** True for states where the obligation is still in flight. */
    public boolean isActive() {
        return this == OPEN || this == ACKNOWLEDGED;
    }
}

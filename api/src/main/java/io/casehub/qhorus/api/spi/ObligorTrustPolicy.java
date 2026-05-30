package io.casehub.qhorus.api.spi;

/**
 * SPI for evaluating whether an obligor is trusted to fulfil a COMMAND commitment.
 *
 * <p>Invoked by {@code MessageService.dispatch()} for COMMAND messages with a named
 * (non-prefixed) target. Role- and capability-prefixed targets bypass the gate —
 * there is no specific obligor to evaluate.
 *
 * <p>The default implementation reads {@code casehub.qhorus.commitment.min-obligor-trust}
 * and delegates to {@code TrustGateService.meetsThreshold()}. Override with
 * {@code @Alternative @Priority(1)} to provide capability-scoped or channel-aware
 * trust evaluation.
 *
 * <p>Refs #213.
 */
@FunctionalInterface
public interface ObligorTrustPolicy {

    /**
     * Returns {@code true} if the obligor identified by {@link ObligorTrustContext#obligorId()}
     * is trusted to act as commitment fulfiller for a COMMAND on the given channel.
     *
     * @param ctx context including obligor identity and channel coordinates
     * @return {@code true} to allow the COMMAND; {@code false} to reject it
     */
    boolean permits(ObligorTrustContext ctx);
}

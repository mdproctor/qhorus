package io.casehub.qhorus.api.spi;

/**
 * The result of a {@link ChannelProjection} fold: materialised state plus the
 * ID of the last message folded ({@code lastMessageId}).
 *
 * <p>{@code lastMessageId} is {@code null} when the channel was empty — no messages
 * were folded. Use {@link #isEmpty()} to distinguish this case.
 *
 * <p>Pass this as {@code previous} to the incremental {@code project()} overload
 * on {@code ProjectionService} to resume folding from the cursor without re-reading
 * earlier messages:
 *
 * <pre>{@code
 * var result = service.project(channelId, projection);    // full scan
 * // ... later, after new messages arrive ...
 * result = service.project(channelId, result, projection); // incremental
 * }</pre>
 *
 * <p><strong>Contract:</strong> only pass results obtained from
 * {@code ProjectionService.project()} — never construct manually. A manually
 * constructed instance with a non-null {@code state} and a null {@code lastMessageId}
 * has undefined behaviour in the incremental overload: the service treats
 * {@code lastMessageId == null} as "channel was empty, start from identity()"
 * and will silently ignore the provided {@code state}.
 *
 * @param <S> the projection state type
 */
public record ProjectionResult<S>(S state, Long lastMessageId) {

    /**
     * Returns {@code true} when the channel was empty at the time of the fold —
     * no messages were processed and {@link #state()} equals {@code identity()}.
     */
    public boolean isEmpty() {
        return lastMessageId == null;
    }
}

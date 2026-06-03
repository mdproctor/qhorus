package io.casehub.qhorus.api.spi;

import io.casehub.qhorus.api.message.MessageView;

/**
 * A pure left-fold over a channel's message history.
 *
 * <p>Implement this SPI to derive a deterministic read-model ({@code S}) from a
 * sequence of typed messages. {@code ProjectionService} reads the channel's message
 * history, folds it step-by-step via {@link #apply}, and returns a
 * {@link ProjectionResult} containing the materialised state and a cursor.
 *
 * <p><strong>Contract — implementors must honour all of these:</strong>
 * <ul>
 *   <li>{@link #identity()} must return a <em>fresh</em> instance on every call.
 *       If {@code S} is mutable (e.g. a {@code HashMap} accumulator), returning a
 *       cached singleton creates shared state across concurrent {@code project()} calls
 *       and will produce incorrect results.</li>
 *   <li>{@link #apply} must be <em>pure</em>: no external state reads or writes,
 *       no side effects, no thread-local access. Return {@code state} unchanged for
 *       messages this projection does not handle — do not return {@code null}.</li>
 *   <li>{@link #apply} must not throw. If it does (unchecked), the exception propagates
 *       from {@code project()} without partial-state recovery.</li>
 * </ul>
 *
 * <p><strong>Rendering:</strong> {@code ProjectionService} returns the typed state {@code S}.
 * Consumers convert {@code S} to an output format (markdown, JSON, etc.) themselves —
 * for example with a {@code Function<S, String>} or a purpose-built renderer.
 * The service never calls a render method; rendering is a consumer-side concern.
 *
 * <p><strong>Registries — two planned models, orthogonal:</strong>
 * <ul>
 *   <li><em>Explicit selection</em> ({@link RenderableProjection} + {@code ProjectionRegistry},
 *       current): implement {@link RenderableProjection} and declare
 *       {@link RenderableProjection#projectionName()} — the tool caller names the projection
 *       explicitly via {@code project_channel("my-channel", "summary")}.</li>
 *   <li><em>Automatic routing</em> ({@code @ChannelBound}, future): a channel name routes to a
 *       projection automatically without tool-caller involvement — designed for dashboards and
 *       automated read-models. A {@link RenderableProjection} bean can carry {@code @ChannelBound}
 *       as well; the two mechanisms are orthogonal qualifiers on the same bean.</li>
 * </ul>
 *
 * @param <S> the state type materialised by this projection
 */
public interface ChannelProjection<S> {

    /**
     * Returns the neutral element — the empty initial state before any messages are folded.
     *
     * <p>Called once per {@code project()} invocation. Must return a fresh instance.
     */
    S identity();

    /**
     * Pure fold step: given the current accumulated {@code state} and the next
     * {@code message}, return the next state.
     *
     * <p>Return {@code state} unchanged for messages this projection ignores.
     * Never return {@code null}.
     *
     * @param state   current accumulated state — never {@code null}
     * @param message the next message to fold — never {@code null}
     * @return the updated state — must not be {@code null}
     */
    S apply(S state, MessageView message);
}

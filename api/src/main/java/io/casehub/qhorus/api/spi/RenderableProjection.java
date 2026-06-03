package io.casehub.qhorus.api.spi;

import io.casehub.qhorus.api.message.MessageView;

/**
 * A {@link ChannelProjection} that can render its materialised state as a String.
 *
 * <p>Extend this interface to register a projection with {@link ProjectionRegistry}
 * for use via the {@code project_channel} MCP tool. Implement all three methods:
 * {@link #projectionName()} for registry lookup, the inherited fold methods from
 * {@link ChannelProjection}, and {@link #render(ProjectionResult)} for the tool output.
 *
 * <p><strong>Registry:</strong> {@code ProjectionRegistry} (in the runtime module)
 * collects all CDI beans implementing this interface at startup, indexed by
 * {@link #projectionName()}. Names must be unique across all registered beans;
 * a duplicate is detected at deployment time and fails fast.
 *
 * <p><strong>Registries — two models, orthogonal:</strong>
 * <ul>
 *   <li><em>Explicit selection</em> (this interface): the tool caller names the
 *       projection — {@code project_channel("my-channel", "summary")}. The
 *       {@code ProjectionRegistry} selects by {@link #projectionName()}.</li>
 *   <li><em>Automatic routing</em> (future {@code @ChannelBound}): the channel name
 *       routes to a projection automatically, without tool-caller involvement.
 *       Designed for dashboards and automated read-models.
 *       A {@code RenderableProjection} bean can also carry {@code @ChannelBound}
 *       — the two mechanisms are orthogonal qualifiers on the same bean.</li>
 * </ul>
 *
 * <p><strong>Multi-format rendering:</strong> to render the same projection in
 * multiple formats, create separate beans with distinct {@link #projectionName()}
 * values (e.g. {@code "summary-markdown"} and {@code "summary-json"}) and share the
 * fold logic via delegation. Do not use a format parameter on {@link #render}.
 *
 * <p><strong>CDI scope:</strong> {@code @ApplicationScoped} is recommended. Beans
 * must be stateless — fold state lives in {@link ProjectionResult}, not in the bean.
 * {@code @Dependent} is permitted; the registry holds the reference for the
 * application lifetime, which is the effective scope.
 *
 * <p><strong>Contract:</strong>
 * <ul>
 *   <li>{@link #projectionName()} must return a non-null, non-empty, stable identifier.</li>
 *   <li>{@link #render(ProjectionResult)} must return a non-null, non-empty String,
 *       including when {@code result.isEmpty() == true} (empty channel). Use
 *       {@link ProjectionResult#isEmpty()} rather than checking whether {@code state}
 *       equals {@code identity()} — they are not equivalent (e.g. a COMMAND counter
 *       on a channel with only EVENTs also produces {@code identity()} but is not empty).</li>
 *   <li>{@link #render(ProjectionResult)} must be pure and non-blocking — called on
 *       the MCP dispatch thread. Must not throw — unchecked exceptions propagate
 *       from {@code project_channel}.</li>
 * </ul>
 *
 * <p>Refs qhorus#232.
 *
 * @param <S> the state type materialised by the fold
 */
public interface RenderableProjection<S> extends ChannelProjection<S> {

    /**
     * The name under which this projection is registered in {@code ProjectionRegistry}.
     *
     * <p>Must be unique across all {@code RenderableProjection} beans in the CDI
     * context. A duplicate detected at startup fails with {@code IllegalStateException}.
     * Use a stable, meaningful identifier — callers reference this from MCP tool arguments.
     *
     * @return non-null, non-empty projection name
     */
    String projectionName();

    /**
     * Converts the fold result to a String suitable for return from the
     * {@code project_channel} MCP tool.
     *
     * <p>The full {@link ProjectionResult} is passed rather than just the state
     * because {@code state == identity()} is ambiguous: it may mean the channel is
     * empty, or that the fold produced no output for this projection's criteria
     * (e.g. a COMMAND counter on a channel with only EVENTs). Only
     * {@link ProjectionResult#isEmpty()} gives the definitive empty-channel signal.
     *
     * @param result the completed fold result — never {@code null}
     * @return a non-null, non-empty String; a human-readable "empty" message
     *         when {@code result.isEmpty() == true}
     */
    String render(ProjectionResult<S> result);
}

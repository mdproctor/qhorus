package io.casehub.qhorus.runtime.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.spi.RenderableProjection;

/**
 * CDI registry for {@link RenderableProjection} beans — built at startup via
 * {@code @Any Instance<RenderableProjection<?>>} iteration and indexed by
 * {@link RenderableProjection#projectionName()}.
 *
 * <p>Duplicate name detection happens at construction time, so any conflict fails
 * at deployment rather than at the first {@code project_channel} tool call.
 *
 * <p>Follows the {@code ChannelBackend.backendId()} pattern: projections identify
 * themselves via a {@code projectionName()} method rather than a CDI qualifier
 * annotation, keeping {@code api/} free of CDI annotation dependencies.
 *
 * <p>Refs qhorus#232.
 */
@ApplicationScoped
public class ProjectionRegistry {

    private final Map<String, RenderableProjection<?>> registry;

    @Inject
    ProjectionRegistry(@Any final Instance<RenderableProjection<?>> bundles) {
        this(buildMap(bundles));
    }

    /** For unit testing without CDI — mirrors the {@link ProjectionService} pattern. */
    ProjectionRegistry(final List<? extends RenderableProjection<?>> projections) {
        this(buildMap(projections));
    }

    private ProjectionRegistry(final Map<String, RenderableProjection<?>> registry) {
        this.registry = registry;
    }

    private static Map<String, RenderableProjection<?>> buildMap(
            final Iterable<? extends RenderableProjection<?>> projections) {
        final Map<String, RenderableProjection<?>> map = new HashMap<>();
        for (final RenderableProjection<?> p : projections) {
            final String name = p.projectionName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        p.getClass().getName() + ".projectionName() returned null or blank — "
                        + "each RenderableProjection must return a non-null, non-empty name");
            }
            if (map.put(name, p) != null) {
                throw new IllegalStateException(
                        "Duplicate RenderableProjection name '" + name + "' — "
                        + "each projection must have a unique projectionName()");
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Looks up a registered projection by name, capturing the state type.
     *
     * @throws IllegalArgumentException if no projection is registered with that name
     */
    @SuppressWarnings("unchecked")
    public <S> RenderableProjection<S> get(final String name) {
        final RenderableProjection<?> p = registry.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                    "No projection registered with name '" + name + "'. Available: "
                    + registry.keySet());
        }
        return (RenderableProjection<S>) p;
    }

    /** All registered projection names — available for a future {@code list_projections} tool. */
    public Set<String> registeredNames() {
        return registry.keySet();
    }
}

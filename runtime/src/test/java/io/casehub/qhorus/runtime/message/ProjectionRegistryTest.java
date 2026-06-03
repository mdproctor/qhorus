package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.runtime.message.ProjectionRegistry;

/**
 * CDI-free unit tests for ProjectionRegistry — direct instantiation via the
 * package-private List constructor, mirroring the ProjectionService pattern.
 */
class ProjectionRegistryTest {

    private static RenderableProjection<?> stub(String name) {
        return new RenderableProjection<String>() {
            @Override public String projectionName() { return name; }
            @Override public String identity() { return ""; }
            @Override public String apply(String state, MessageView msg) { return state; }
            @Override public String render(ProjectionResult<String> r) { return r.isEmpty() ? "empty" : r.state(); }
        };
    }

    // Java nested-wildcard inference: List.of() with wildcard elements requires explicit type witness.
    private static List<RenderableProjection<?>> stubs(String... names) {
        List<RenderableProjection<?>> list = new ArrayList<>();
        for (String name : names) list.add(stub(name));
        return list;
    }

    @Test
    void get_returnsCorrectProjection_byName() {
        ProjectionRegistry registry = new ProjectionRegistry(stubs("alpha", "beta"));

        assertThat(registry.get("alpha").projectionName()).isEqualTo("alpha");
        assertThat(registry.get("beta").projectionName()).isEqualTo("beta");
    }

    @Test
    void get_unknownName_throwsIllegalArgumentException() {
        ProjectionRegistry registry = new ProjectionRegistry(stubs("only"));

        assertThatThrownBy(() -> registry.get("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("only");
    }

    @Test
    void registeredNames_returnsAllNames() {
        ProjectionRegistry registry = new ProjectionRegistry(stubs("x", "y", "z"));

        assertThat(registry.registeredNames()).containsExactlyInAnyOrder("x", "y", "z");
    }

    @Test
    void emptyRegistry_get_throwsWithUsefulMessage() {
        ProjectionRegistry registry = new ProjectionRegistry(stubs());

        assertThatThrownBy(() -> registry.get("anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anything");
    }

    @Test
    void emptyRegistry_registeredNames_returnsEmptySet() {
        ProjectionRegistry registry = new ProjectionRegistry(stubs());
        assertThat(registry.registeredNames()).isEmpty();
    }

    @Test
    void duplicateName_throwsIllegalStateException_atConstruction() {
        assertThatThrownBy(() -> new ProjectionRegistry(stubs("summary", "summary")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void nullProjectionName_throwsIllegalStateException_atConstruction() {
        RenderableProjection<?> nullNamed = new RenderableProjection<String>() {
            @Override public String projectionName() { return null; }
            @Override public String identity() { return ""; }
            @Override public String apply(String s, MessageView m) { return s; }
            @Override public String render(ProjectionResult<String> r) { return ""; }
        };
        List<RenderableProjection<?>> list = new ArrayList<>();
        list.add(nullNamed);

        assertThatThrownBy(() -> new ProjectionRegistry(list))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void blankProjectionName_throwsIllegalStateException_atConstruction() {
        RenderableProjection<?> blankNamed = new RenderableProjection<String>() {
            @Override public String projectionName() { return "  "; }
            @Override public String identity() { return ""; }
            @Override public String apply(String s, MessageView m) { return s; }
            @Override public String render(ProjectionResult<String> r) { return ""; }
        };
        List<RenderableProjection<?>> list = new ArrayList<>();
        list.add(blankNamed);

        assertThatThrownBy(() -> new ProjectionRegistry(list))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void get_returnsSameInstance() {
        RenderableProjection<?> p = stub("typed");
        ProjectionRegistry registry = new ProjectionRegistry(new ArrayList<>(List.of(p)));

        assertThat(registry.get("typed")).isSameAs(p);
    }
}

package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AllowedWritersPolicyTest {

    private AllowedWritersPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AllowedWritersPolicy();
    }

    @Test void null_acl_allows_any_sender() {
        assertThat(policy.isAllowedWriter("agent-x", null, List::of)).isTrue();
    }

    @Test void empty_acl_allows_any_sender() {
        assertThat(policy.isAllowedWriter("agent-x", List.of(), List::of)).isTrue();
    }

    @Test void exact_match_allows_sender() {
        assertThat(policy.isAllowedWriter("agent-x", List.of("agent-x", "agent-y"), List::of)).isTrue();
    }

    @Test void exact_match_second_entry_allows() {
        assertThat(policy.isAllowedWriter("agent-y", List.of("agent-x", "agent-y"), List::of)).isTrue();
    }

    @Test void exact_match_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter("agent-z", List.of("agent-x", "agent-y"), List::of)).isFalse();
    }

    @Test void capability_tag_match_allows_sender() {
        assertThat(policy.isAllowedWriter(
                "agent-x", List.of("capability:analysis"),
                () -> List.of("capability:analysis", "capability:review"))).isTrue();
    }

    @Test void capability_tag_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter(
                "agent-x", List.of("capability:analysis"),
                () -> List.of("capability:review"))).isFalse();
    }

    @Test void capability_supplier_not_invoked_for_exact_acl() {
        final boolean[] supplierCalled = {false};
        policy.isAllowedWriter("agent-x", List.of("agent-x"), () -> {
            supplierCalled[0] = true;
            return List.of("capability:analysis");
        });
        assertThat(supplierCalled[0]).isFalse();
    }

    @Test void capability_supplier_not_invoked_for_mixed_acl_when_exact_matches_first() {
        final boolean[] supplierCalled = {false};
        final boolean result = policy.isAllowedWriter("agent-x", List.of("agent-x", "capability:analysis"), () -> {
            supplierCalled[0] = true;
            return List.of("capability:analysis");
        });
        assertThat(result).isTrue();
        assertThat(supplierCalled[0]).isFalse();
    }

    @Test void role_tag_match_allows_sender() {
        assertThat(policy.isAllowedWriter(
                "some-agent", List.of("role:agent"),
                () -> List.of("role:agent"))).isTrue();
    }

    @Test void role_tag_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter(
                "some-agent", List.of("role:agent"),
                () -> List.of("role:human"))).isFalse();
    }

    @Test void synthetic_role_agent_allows_a2a_sender_for_role_acl() {
        assertThat(policy.isAllowedWriter(
                "external-agent", List.of("role:agent"),
                () -> List.of("role:agent"))).isTrue();
    }

    @Test void capability_acl_blocks_unregistered_a2a_sender() {
        assertThat(policy.isAllowedWriter(
                "external-agent", List.of("capability:analysis"),
                () -> List.of("role:agent"))).isFalse();
    }
}

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

    // ── Null / blank ACL = open ───────────────────────────────────────────────

    @Test void null_acl_allows_any_sender() {
        assertThat(policy.isAllowedWriter("agent-x", null, List::of)).isTrue();
    }

    @Test void blank_acl_allows_any_sender() {
        assertThat(policy.isAllowedWriter("agent-x", "   ", List::of)).isTrue();
    }

    // ── Exact sender match ────────────────────────────────────────────────────

    @Test void exact_match_allows_sender() {
        assertThat(policy.isAllowedWriter("agent-x", "agent-x,agent-y", List::of)).isTrue();
    }

    @Test void exact_match_second_entry_allows() {
        assertThat(policy.isAllowedWriter("agent-y", "agent-x,agent-y", List::of)).isTrue();
    }

    @Test void exact_match_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter("agent-z", "agent-x,agent-y", List::of)).isFalse();
    }

    // ── Capability tag match ──────────────────────────────────────────────────

    @Test void capability_tag_match_allows_sender() {
        assertThat(policy.isAllowedWriter(
                "agent-x", "capability:analysis",
                () -> List.of("capability:analysis", "capability:review"))).isTrue();
    }

    @Test void capability_tag_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter(
                "agent-x", "capability:analysis",
                () -> List.of("capability:review"))).isFalse();
    }

    @Test void capability_supplier_not_invoked_for_exact_acl() {
        // Supplier must be lazy — not called when ACL only has exact-ID entries
        final boolean[] supplierCalled = {false};
        policy.isAllowedWriter("agent-x", "agent-x", () -> {
            supplierCalled[0] = true;
            return List.of("capability:analysis");
        });
        assertThat(supplierCalled[0]).isFalse();
    }

    @Test void capability_supplier_not_invoked_for_mixed_acl_when_exact_matches_first() {
        // Mixed ACL: exact ID followed by a tag entry. Exact match succeeds before tag is reached — supplier skipped.
        final boolean[] supplierCalled = {false};
        final boolean result = policy.isAllowedWriter("agent-x", "agent-x,capability:analysis", () -> {
            supplierCalled[0] = true;
            return List.of("capability:analysis");
        });
        assertThat(result).isTrue();
        assertThat(supplierCalled[0]).isFalse();
    }

    // ── Role tag match ────────────────────────────────────────────────────────

    @Test void role_tag_match_allows_sender() {
        assertThat(policy.isAllowedWriter(
                "some-agent", "role:agent",
                () -> List.of("role:agent"))).isTrue();
    }

    @Test void role_tag_missing_blocks_sender() {
        assertThat(policy.isAllowedWriter(
                "some-agent", "role:agent",
                () -> List.of("role:human"))).isFalse();
    }

    // ── Unregistered A2A sender (synthetic role tag) ──────────────────────────

    @Test void synthetic_role_agent_allows_a2a_sender_for_role_acl() {
        // A2A senders are unregistered; supplier returns only synthetic "role:agent"
        assertThat(policy.isAllowedWriter(
                "external-agent", "role:agent",
                () -> List.of("role:agent"))).isTrue();
    }

    @Test void capability_acl_blocks_unregistered_a2a_sender() {
        // External agents have no attested capability tags — correct to block
        assertThat(policy.isAllowedWriter(
                "external-agent", "capability:analysis",
                () -> List.of("role:agent"))).isFalse();
    }

    // ── Whitespace and empty entries ignored ──────────────────────────────────

    @Test void whitespace_around_entries_trimmed() {
        assertThat(policy.isAllowedWriter("agent-x", " agent-x , agent-y ", List::of)).isTrue();
    }

    @Test void empty_entry_in_acl_ignored() {
        assertThat(policy.isAllowedWriter("agent-x", ",,agent-x,,", List::of)).isTrue();
    }
}

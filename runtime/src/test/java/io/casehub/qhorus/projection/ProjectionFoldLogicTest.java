package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;

/**
 * Pure unit tests for fold logic — no framework, no store, no CDI.
 * The fold function is a pure function; test it directly.
 */
class ProjectionFoldLogicTest {

    // ── Simple vote-tally projection for testing ──────────────────────────────

    record VoteState(int approvals, int declines) {}

    static final class VoteTallyProjection implements ChannelProjection<VoteState> {
        @Override
        public VoteState identity() {
            return new VoteState(0, 0);
        }

        @Override
        public VoteState apply(final VoteState state, final MessageView message) {
            return switch (message.type()) {
                case COMMAND -> new VoteState(state.approvals() + 1, state.declines());
                case DECLINE -> new VoteState(state.approvals(), state.declines() + 1);
                default -> state;
            };
        }
    }

    // ── identity() contract ───────────────────────────────────────────────────

    @Test
    void identity_returnsZeroCounts() {
        final var proj = new VoteTallyProjection();
        final var state = proj.identity();
        assertThat(state.approvals()).isZero();
        assertThat(state.declines()).isZero();
    }

    @Test
    void identity_returnsFreshInstanceEachCall() {
        final var proj = new VoteTallyProjection();
        final var s1 = proj.identity();
        final var s2 = proj.identity();
        // For immutable records this is trivially true — they cannot share state.
        // The dangerous case is mutable accumulators; see the test below.
        assertThat(s1).isEqualTo(s2);          // same value
        assertThat(s1).isNotSameAs(s2);        // different objects
    }

    @Test
    void identity_mutableAccumulator_returnsSeparateInstancesPerCall() {
        // This is the case that breaks in production: a cached singleton mutable
        // accumulator is shared across concurrent project() calls.
        final ChannelProjection<List<String>> proj = new ChannelProjection<>() {
            @Override
            public List<String> identity() {
                return new ArrayList<>(); // must be a fresh list each call
            }

            @Override
            public List<String> apply(final List<String> state, final MessageView message) {
                state.add(message.sender());
                return state;
            }
        };

        final var list1 = proj.identity();
        final var list2 = proj.identity();

        list1.add("alice");

        // Mutating list1 must not affect list2
        assertThat(list1).containsExactly("alice");
        assertThat(list2).isEmpty();
        assertThat(list1).isNotSameAs(list2);
    }

    // ── apply() contract ──────────────────────────────────────────────────────

    @Test
    void apply_commandIncreasesApprovals() {
        final var proj = new VoteTallyProjection();
        final var state = proj.apply(proj.identity(), view(MessageType.COMMAND));
        assertThat(state.approvals()).isEqualTo(1);
        assertThat(state.declines()).isZero();
    }

    @Test
    void apply_declineIncreasesDeclines() {
        final var proj = new VoteTallyProjection();
        final var state = proj.apply(proj.identity(), view(MessageType.DECLINE));
        assertThat(state.approvals()).isZero();
        assertThat(state.declines()).isEqualTo(1);
    }

    @Test
    void apply_ignoredTypesReturnUnchangedState() {
        final var proj = new VoteTallyProjection();
        final var initial = proj.identity();
        for (final var type : new MessageType[]{
                MessageType.QUERY, MessageType.RESPONSE, MessageType.STATUS,
                MessageType.EVENT, MessageType.DONE, MessageType.FAILURE,
                MessageType.HANDOFF}) {
            final var result = proj.apply(initial, view(type));
            assertThat(result).isSameAs(initial);
        }
    }

    // ── full fold sequence ────────────────────────────────────────────────────

    @Test
    void foldSequence_accumulatesAllMessages() {
        final var proj = new VoteTallyProjection();
        var state = proj.identity();
        state = proj.apply(state, view(MessageType.COMMAND));   // approve
        state = proj.apply(state, view(MessageType.COMMAND));   // approve
        state = proj.apply(state, view(MessageType.DECLINE));   // decline
        state = proj.apply(state, view(MessageType.EVENT));     // ignored
        state = proj.apply(state, view(MessageType.COMMAND));   // approve

        assertThat(state.approvals()).isEqualTo(3);
        assertThat(state.declines()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageView view(final MessageType type) {
        return new MessageView(
                1L, UUID.randomUUID(), "agent-a", type,
                "content", null, null, null, null,
                ActorType.AGENT, Instant.now(), null, 0);
    }
}

package io.casehub.qhorus.api.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CommitmentStateTest {

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = { "OPEN", "ACKNOWLEDGED" })
    void activeStates_returnTrue(CommitmentState state) {
        assertThat(state.isActive()).isTrue();
        assertThat(state.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = { "FULFILLED", "DECLINED", "FAILED", "DELEGATED", "EXPIRED" })
    void terminalStates_returnTrue(CommitmentState state) {
        assertThat(state.isTerminal()).isTrue();
        assertThat(state.isActive()).isFalse();
    }

    @Test
    void everyState_classifiedByExactlyOneMethod() {
        for (CommitmentState state : CommitmentState.values()) {
            assertThat(state.isTerminal() ^ state.isActive())
                    .as("State %s must be classified by exactly one of isTerminal() or isActive()", state)
                    .isTrue();
        }
    }

    @Test
    void activeAndTerminal_coverAllStates() {
        EnumSet<CommitmentState> active = EnumSet.noneOf(CommitmentState.class);
        EnumSet<CommitmentState> terminal = EnumSet.noneOf(CommitmentState.class);

        for (CommitmentState state : CommitmentState.values()) {
            if (state.isActive()) active.add(state);
            if (state.isTerminal()) terminal.add(state);
        }

        EnumSet<CommitmentState> all = EnumSet.allOf(CommitmentState.class);
        EnumSet<CommitmentState> union = EnumSet.copyOf(active);
        union.addAll(terminal);

        assertThat(union).as("Union of active and terminal must cover all states").isEqualTo(all);
        assertThat(active).as("Active and terminal must not overlap").doesNotContainAnyElementsOf(terminal);
    }
}

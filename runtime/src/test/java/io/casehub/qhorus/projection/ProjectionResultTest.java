package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.spi.ProjectionResult;

class ProjectionResultTest {

    @Test
    void isEmpty_trueWhenLastMessageIdNull() {
        var result = new ProjectionResult<>("state", null);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_falseWhenLastMessageIdSet() {
        var result = new ProjectionResult<>("state", 42L);
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void state_returnsProvidedState() {
        var result = new ProjectionResult<>(99, 1L);
        assertThat(result.state()).isEqualTo(99);
    }

    @Test
    void lastMessageId_returnsProvidedId() {
        var result = new ProjectionResult<>("x", 7L);
        assertThat(result.lastMessageId()).isEqualTo(7L);
    }
}

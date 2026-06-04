package io.casehub.qhorus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;

/**
 * Verifies that ReactiveChannelService.create() runs ChannelCreateRequest validation
 * (the D1 guarantee) before entering the Panache transaction — so overlapping or invalid
 * type names are rejected immediately, without any DB interaction.
 *
 * <p>Tests use direct instantiation (no CDI) because the exception fires in the
 * ChannelCreateRequest compact constructor, before any injected store is accessed.
 */
class ReactiveChannelServiceDeniedTypesTest {

    private final ReactiveChannelService svc = new ReactiveChannelService();

    @Test
    void overlappingAllowedAndDenied_throwsBeforeTransaction() {
        assertThatThrownBy(() ->
                svc.create("ch", null, ChannelSemantic.APPEND,
                        null, null, null, null, null, "QUERY", "QUERY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intersect");
    }

    @Test
    void invalidTypeNameInDenied_throwsBeforeTransaction() {
        assertThatThrownBy(() ->
                svc.create("ch", null, ChannelSemantic.APPEND,
                        null, null, null, null, null, null, "INVALID_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTypeNameInAllowed_throwsBeforeTransaction() {
        assertThatThrownBy(() ->
                svc.create("ch", null, ChannelSemantic.APPEND,
                        null, null, null, null, null, "BOGUS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

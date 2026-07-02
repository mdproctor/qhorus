package io.casehub.qhorus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;

/**
 * Verifies that ReactiveChannelService.create() runs ChannelCreateRequest validation
 * (the D1 guarantee) before entering the Panache transaction — so overlapping types
 * are rejected immediately, without any DB interaction.
 *
 * <p>With typed {@code Set<MessageType>} fields, invalid type names are a compile error,
 * not a runtime exception — no test needed for that case.
 *
 * <p>Tests use direct instantiation (no CDI) because the exception fires in the
 * ChannelCreateRequest compact constructor, before any injected store is accessed.
 */
class ReactiveChannelServiceDeniedTypesTest {

    private final ReactiveChannelService svc = new ReactiveChannelService();

    @Test
    void overlappingAllowedAndDenied_throwsBeforeTransaction() {
        assertThatThrownBy(() ->
                svc.create(ChannelCreateRequest.builder("ch")
                        .allowedTypes(Set.of(MessageType.QUERY))
                        .deniedTypes(Set.of(MessageType.QUERY))
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intersect");
    }
}

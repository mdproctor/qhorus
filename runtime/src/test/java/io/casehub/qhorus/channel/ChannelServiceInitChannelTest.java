package io.casehub.qhorus.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that {@link ChannelService#create()} fires {@link ChannelInitialisedEvent}
 * so that {@code ChannelBackend} implementations can self-register for runtime-created
 * channels without requiring the caller to invoke {@code ChannelGateway.initChannel()}.
 * Refs #254.
 */
@QuarkusTest
class ChannelServiceInitChannelTest {

    @Inject ChannelService channelService;
    @Inject EventCapture eventCapture;

    @Test
    void create_firesChannelInitialisedEvent() {
        final String          name    = "test-init-254-" + UUID.randomUUID();
        final Channel[] created = new Channel[1];

        QuarkusTransaction.requiringNew().run(() ->
                created[0] = channelService.create(ChannelCreateRequest.builder(name).description("Test").build()));

        assertThat(eventCapture.channelIds())
                .as("ChannelInitialisedEvent must fire for runtime-created channel")
                .contains(created[0].id());
    }

    @ApplicationScoped
    public static class EventCapture {
        private final List<UUID> captured = new CopyOnWriteArrayList<>();

        void onEvent(@Observes ChannelInitialisedEvent event) {
            captured.add(event.channelId());
        }

        List<UUID> channelIds() { return List.copyOf(captured); }
    }
}

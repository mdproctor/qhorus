package io.casehub.qhorus.runtime.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.runtime.StartupEvent;

class ChannelGatewayStartupTest {

    @SuppressWarnings("unchecked")
    private ChannelGateway gatewayWith(ChannelService channelService) {
        return new ChannelGateway(
                new QhorusChannelBackend(),
                new DefaultInboundNormaliser(),
                mock(MessageService.class),
                channelService,
                mock(Event.class));
    }

    private Channel channel(String name) {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = name;
        ch.semantic = ChannelSemantic.APPEND;
        return ch;
    }

    @Test
    void onStart_registersAgentBackend_forEachPersistedChannel() {
        Channel ch1 = channel("case-1/work");
        Channel ch2 = channel("case-2/observe");
        ChannelService channelService = mock(ChannelService.class);
        when(channelService.listAll()).thenReturn(List.of(ch1, ch2));
        ChannelGateway gateway = gatewayWith(channelService);

        gateway.onStart(new StartupEvent());

        assertTrue(gateway.listBackends(ch1.id).stream().anyMatch(b -> "qhorus-internal".equals(b.backendId())),
                "ch1 should have agent backend after startup");
        assertTrue(gateway.listBackends(ch2.id).stream().anyMatch(b -> "qhorus-internal".equals(b.backendId())),
                "ch2 should have agent backend after startup");
        verify(channelService).listAll();
    }

    @Test
    void onStart_withNoChannels_doesNothing() {
        ChannelService channelService = mock(ChannelService.class);
        when(channelService.listAll()).thenReturn(List.of());
        ChannelGateway gateway = gatewayWith(channelService);

        assertDoesNotThrow(() -> gateway.onStart(new StartupEvent()));
        verify(channelService).listAll();
    }

    @Test
    void onStart_continuesInitialising_whenOneChannelThrows() {
        Channel bad = channel("bad-channel");
        Channel good = channel("good-channel");

        @SuppressWarnings("unchecked")
        Event<ChannelInitialisedEvent> throwingEvents = mock(Event.class);
        // First fire throws, second succeeds
        doThrow(new RuntimeException("observer failure"))
                .doNothing()
                .when(throwingEvents).fire(any());

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.listAll()).thenReturn(List.of(bad, good));

        ChannelGateway gateway = new ChannelGateway(
                new QhorusChannelBackend(),
                new DefaultInboundNormaliser(),
                mock(MessageService.class),
                channelService,
                throwingEvents);

        assertDoesNotThrow(() -> gateway.onStart(new StartupEvent()),
                "onStart must not propagate observer exceptions");
        // good channel should still be registered despite bad channel failing
        assertTrue(gateway.listBackends(good.id).stream()
                .anyMatch(b -> "qhorus-internal".equals(b.backendId())));
    }

    @Test
    void onStart_isIdempotent_whenChannelAlreadyInitialised() {
        Channel ch = channel("case-1/work");
        ChannelService channelService = mock(ChannelService.class);
        when(channelService.listAll()).thenReturn(List.of(ch));
        ChannelGateway gateway = gatewayWith(channelService);

        gateway.initChannel(ch.id, new ChannelRef(ch.id, ch.name));
        long countBefore = gateway.listBackends(ch.id).stream()
                .filter(b -> "qhorus-internal".equals(b.backendId())).count();

        gateway.onStart(new StartupEvent());

        long countAfter = gateway.listBackends(ch.id).stream()
                .filter(b -> "qhorus-internal".equals(b.backendId())).count();
        assertEquals(countBefore, countAfter,
                "onStart must not duplicate agent backend for already-initialised channel");
    }
}

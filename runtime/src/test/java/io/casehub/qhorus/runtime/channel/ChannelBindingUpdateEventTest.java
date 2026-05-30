package io.casehub.qhorus.runtime.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.casehub.qhorus.runtime.store.MessageStore;

/**
 * Pure-unit test (no CDI) for ChannelService.updateConnectorBinding() event firing.
 * Verifies that ChannelInitialisedEvent is fired with the correct channel coordinates.
 * Refs #215
 */
@SuppressWarnings("unchecked")
class ChannelBindingUpdateEventTest {

    ChannelStore channelStore;
    ChannelBindingStore bindingStore;
    MessageStore messageStore;
    Event<ChannelInitialisedEvent> events;
    ChannelService service;

    @BeforeEach
    void setup() {
        channelStore = mock(ChannelStore.class);
        bindingStore = mock(ChannelBindingStore.class);
        messageStore = mock(MessageStore.class);
        events = mock(Event.class);

        service = new ChannelService();
        service.channelStore = channelStore;
        service.channelBindingStore = bindingStore;
        service.messageStore = messageStore;
        service.channelInitialisedEvents = events;
    }

    @Test
    void updateConnectorBinding_firesEventWithChannelIdAndName() {
        UUID channelId = UUID.randomUUID();
        Channel ch = new Channel();
        ch.id = channelId;
        ch.name = "my-channel";
        ChannelConnectorBinding binding = new ChannelConnectorBinding();
        binding.channelId = channelId;
        binding.outboundConnectorId = "old-connector";
        binding.outboundDestination = "old-dest";

        when(channelStore.find(channelId)).thenReturn(Optional.of(ch));
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.of(binding));

        service.updateConnectorBinding(channelId, "vonage-out", "+447999888777");

        verify(events).fire(new ChannelInitialisedEvent(channelId, "my-channel", false));
    }

    @Test
    void updateConnectorBinding_throwsWhenChannelNotFound() {
        UUID unknown = UUID.randomUUID();
        when(channelStore.find(unknown)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.updateConnectorBinding(unknown, "out", "dest"));

        verifyNoInteractions(events);
    }

    @Test
    void updateConnectorBinding_throwsWhenNoBinding() {
        UUID channelId = UUID.randomUUID();
        Channel ch = new Channel();
        ch.id = channelId;
        ch.name = "no-binding-ch";
        when(channelStore.find(channelId)).thenReturn(Optional.of(ch));
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                service.updateConnectorBinding(channelId, "out", "dest"));

        verifyNoInteractions(events);
    }
}

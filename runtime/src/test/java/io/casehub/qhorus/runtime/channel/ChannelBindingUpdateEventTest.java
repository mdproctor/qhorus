package io.casehub.qhorus.runtime.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;

class ChannelBindingUpdateEventTest {

    ChannelStore channelStore;
    ChannelBindingStore bindingStore;
    MessageStore messageStore;
    ChannelGateway channelGateway;
    ChannelService service;

    @BeforeEach
    void setup() {
        channelStore = mock(ChannelStore.class);
        bindingStore = mock(ChannelBindingStore.class);
        messageStore = mock(MessageStore.class);
        channelGateway = mock(ChannelGateway.class);

        service = new ChannelService();
        service.channelStore = channelStore;
        service.channelBindingStore = bindingStore;
        service.messageStore = messageStore;
        service.channelGateway = channelGateway;
    }

    @Test
    void updateConnectorBinding_callsInitChannelWithChannelIdAndName() {
        final UUID channelId = UUID.randomUUID();
        final Channel ch = Channel.builder("my-channel").id(channelId).build();
        final ChannelConnectorBinding binding = new ChannelConnectorBinding(
                channelId, "old-connector", "old-key", "old-connector", "old-dest");

        when(channelStore.find(channelId)).thenReturn(Optional.of(ch));
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.of(binding));

        service.updateConnectorBinding(channelId, "vonage-out", "+447999888777");

        verify(channelGateway).initChannel(channelId, new ChannelRef(channelId, "my-channel"));
    }

    @Test
    void updateConnectorBinding_throwsWhenChannelNotFound() {
        final UUID unknown = UUID.randomUUID();
        when(channelStore.find(unknown)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.updateConnectorBinding(unknown, "out", "dest"));

        verifyNoInteractions(channelGateway);
    }

    @Test
    void updateConnectorBinding_throwsWhenNoBinding() {
        final UUID channelId = UUID.randomUUID();
        final Channel ch = Channel.builder("no-binding-ch").id(channelId).build();
        when(channelStore.find(channelId)).thenReturn(Optional.of(ch));
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                service.updateConnectorBinding(channelId, "out", "dest"));

        verifyNoInteractions(channelGateway);
    }
}

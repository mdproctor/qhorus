package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.connectors.ConnectorService;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConnectorNormaliserDispatchTest {

    private ConnectorChannelBackend backend;
    private ChannelBindingStore bindingStore;

    @BeforeEach
    void setUp() {
        ChannelGateway gateway = mock(ChannelGateway.class);
        ChannelService channelService = mock(ChannelService.class);
        bindingStore = mock(ChannelBindingStore.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        AutoChannelPolicy autoChannelPolicy = mock(AutoChannelPolicy.class);
        backend = new ConnectorChannelBackend(gateway, channelService, bindingStore,
                connectorService, new SimpleMeterRegistry(), autoChannelPolicy);
    }

    @Test
    void normaliserFor_returnsNull_whenNoCacheEntry() {
        assertThat(backend.normaliserFor(UUID.randomUUID())).isNull();
    }

    @Test
    void normaliserFor_returnsNull_whenNoConnectorNormaliserRegistered() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding b = new ChannelConnectorBinding();
        b.channelId = channelId;
        b.inboundConnectorId = InboundConnectorIds.TWILIO_SMS;
        b.externalKey = "+1111";
        b.outboundConnectorId = "twilio-sms";
        b.outboundDestination = "+9999";
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.of(b));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-channel", false));

        assertThat(backend.normaliserFor(channelId)).isNull();
    }
}

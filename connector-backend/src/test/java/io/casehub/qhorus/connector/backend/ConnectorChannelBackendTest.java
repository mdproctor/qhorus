package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;

class ConnectorChannelBackendTest {

    private ChannelGateway gateway;
    private ChannelService channelService;
    private ChannelBindingStore bindingStore;
    private ConnectorService connectorService;
    private ConnectorChannelBackend backend;

    @BeforeEach
    void setUp() {
        gateway = mock(ChannelGateway.class);
        channelService = mock(ChannelService.class);
        bindingStore = mock(ChannelBindingStore.class);
        connectorService = mock(ConnectorService.class);
        backend = new ConnectorChannelBackend(gateway, channelService, bindingStore, connectorService,
                new SimpleMeterRegistry());
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private ChannelConnectorBinding binding(UUID channelId, String inConnId, String extKey,
            String outConnId, String dest) {
        ChannelConnectorBinding b = new ChannelConnectorBinding();
        b.channelId = channelId;
        b.inboundConnectorId = inConnId;
        b.externalKey = extKey;
        b.outboundConnectorId = outConnId;
        b.outboundDestination = dest;
        return b;
    }

    private Channel channel(UUID id, String name) {
        Channel ch = new Channel();
        ch.id = id;
        ch.name = name;
        return ch;
    }

    // -------------------------------------------------------------------------
    // onChannelInitialised tests
    // -------------------------------------------------------------------------

    @Test
    void onChannelInitialised_registersBackend_whenBindingPresent() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+9999")));

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-alice", false));

        verify(gateway).deregisterBackend(eq(channelId), eq(backend.backendId()));
        verify(gateway).registerBackend(eq(channelId), eq(backend), eq("human_participating"));
    }

    @Test
    void onChannelInitialised_skips_whenNoBinding() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId)).thenReturn(Optional.empty());

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "unbound-channel", false));

        verifyNoInteractions(gateway);
    }

    @Test
    void onChannelInitialised_isIdempotent_cacheUpdatedOnSecondEvent() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding first  = binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+1111");
        ChannelConnectorBinding second = binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+2222");
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-alice", false));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-alice", false));

        ChannelRef ref = new ChannelRef(channelId, "sms-alice");
        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                "hello", null, null, ActorType.AGENT));

        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService).send(eq("twilio-sms"), captor.capture());
        assertThat(captor.getValue().destination()).isEqualTo("+2222");
    }

    // -------------------------------------------------------------------------
    // onInboundMessage tests
    // -------------------------------------------------------------------------

    @Test
    void onInboundMessage_routesToReceiveHumanMessage_whenChannelFound() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1234", "twilio-sms", "+9999")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-bob", false));

        when(channelService.findByConnectorKey(InboundConnectorIds.TWILIO_SMS, "+1234"))
                .thenReturn(Optional.of(channel(channelId, "sms-bob")));

        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, "+1234", "+9999",
                "hello world", Instant.now(), Map.of());
        backend.onInboundMessage(msg);

        ArgumentCaptor<InboundHumanMessage> captor = ArgumentCaptor.forClass(InboundHumanMessage.class);
        ArgumentCaptor<ChannelRef> refCaptor = ArgumentCaptor.forClass(ChannelRef.class);
        verify(gateway).receiveHumanMessage(refCaptor.capture(), captor.capture());

        assertThat(refCaptor.getValue().id()).isEqualTo(channelId);
        assertThat(captor.getValue().externalSenderId()).isEqualTo("+1234");
        assertThat(captor.getValue().content()).isEqualTo("hello world");
    }

    @Test
    void onInboundMessage_noChannelFound_doesNotThrow_andIncrementsCounter() {
        when(channelService.findByConnectorKey(any(), any())).thenReturn(Optional.empty());

        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, "+5555", "+9000",
                "hi", Instant.now(), Map.of());

        assertThatCode(() -> backend.onInboundMessage(msg)).doesNotThrowAnyException();
        verifyNoInteractions(gateway);
        assertThat(backend.discardedCount(InboundConnectorIds.TWILIO_SMS)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // post tests
    // -------------------------------------------------------------------------

    @Test
    void post_sendsViaConnectorService_fromCache() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+9999")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-carol", false));

        ChannelRef ref = new ChannelRef(channelId, "sms-carol");
        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                "reply text", null, null, ActorType.AGENT));

        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService).send(eq("twilio-sms"), captor.capture());
        assertThat(captor.getValue().destination()).isEqualTo("+9999");
        assertThat(captor.getValue().body()).isEqualTo("reply text");
    }

    @Test
    void post_emailConnector_includesReSubjectTitle() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.EMAIL, "alice@example.com", "email", "alice@example.com")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "support-email", false));

        ChannelRef ref = new ChannelRef(channelId, "support-email");
        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                "thank you", null, null, ActorType.AGENT));

        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService).send(eq("email"), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Re: support-email");
    }

    @Test
    void post_noCacheEntry_doesNotThrow() {
        UUID channelId = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(channelId, "ghost-channel");

        assertThatCode(() -> backend.post(ref,
                new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                        "hello", null, null, ActorType.AGENT)))
                .doesNotThrowAnyException();

        verifyNoInteractions(connectorService);
    }

    @Test
    void post_connectorServiceThrows_doesNotPropagate() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+7777", "twilio-sms", "+8888")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-dave", false));

        doThrow(new IllegalArgumentException("connector not found"))
                .when(connectorService).send(anyString(), any());

        ChannelRef ref = new ChannelRef(channelId, "sms-dave");
        assertThatCode(() -> backend.post(ref,
                new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                        "hi", null, null, ActorType.AGENT)))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // close tests
    // -------------------------------------------------------------------------

    @Test
    void close_removesCacheEntry_outboundDropsAfterClose() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1010", "twilio-sms", "+2020")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-eve", false));

        ChannelRef ref = new ChannelRef(channelId, "sms-eve");
        backend.close(ref);

        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE,
                "after close", null, null, ActorType.AGENT));

        verifyNoInteractions(connectorService);
    }

    // -------------------------------------------------------------------------
    // Staleness documentation — disabled pending qhorus#215
    // -------------------------------------------------------------------------

    @Disabled("v2: cache not invalidated on binding update — enable when ChannelInitialisedEvent fires on update (qhorus#215)")
    @Test
    void cacheRefreshesAfterBindingUpdate() {
        UUID channelId = UUID.randomUUID();
        when(bindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+1111")))
                .thenReturn(Optional.of(binding(channelId, InboundConnectorIds.TWILIO_SMS, "+1111", "twilio-sms", "+2222")));
        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "sms-alice", false));
        // Binding updated externally — no second ChannelInitialisedEvent fired
        backend.post(new ChannelRef(channelId, "sms-alice"),
                new OutboundMessage(UUID.randomUUID(), "agent", MessageType.RESPONSE, "hi", null, null, ActorType.AGENT));
        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService).send(eq("twilio-sms"), captor.capture());
        // Fails in v1 — cache still has "+1111"
        assertThat(captor.getValue().destination()).isEqualTo("+2222");
    }
}

package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.persistence.memory.InMemoryChannelBindingStore;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ConnectorChannelBackendIntegrationTest {

    @Inject ConnectorChannelBackend backend;
    @Inject ChannelService channelService;
    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryChannelBindingStore channelBindingStore;
    @Inject Event<InboundMessage> inboundMessageEvent;

    @InjectMock MessageService messageService;
    @InjectMock ConnectorService connectorService;
    @InjectMock CurrentPrincipal currentPrincipal;

    private UUID channelId;

    @BeforeEach
    void setUp() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        channelStore.clear();
        channelBindingStore.clear();

        Channel ch = channelService.create(ChannelCreateRequest.builder("sms-alice")
                                                                     .description("Alice's SMS conversation")
                                                                     .inboundConnectorId(InboundConnectorIds.TWILIO_SMS)
                                                                     .externalKey("+15551110000")
                                                                     .outboundConnectorId("twilio-sms")
                                                                     .outboundDestination("+15551110000")
                                                                     .build());
        channelId = ch.id();
        // initChannel fires @Observes ChannelInitialisedEvent synchronously —
        // ConnectorChannelBackend.onChannelInitialised populates cache before setUp returns.
        gateway.initChannel(ch.id(), new ChannelRef(ch.id(), ch.name()));
    }

    @AfterEach
    void tearDown() {
        gateway.closeChannel(channelId, new ChannelRef(channelId, "sms-alice"));
    }

    @Test
    void inboundMessage_routesToMessageService() {
        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, InboundConnectorTypes.SMS, "+15551110000",
                "+14155552671", "I need help", List.of(), Instant.now(), Map.of(), null);

        // Call through CDI proxy — synchronous; no async waiting required.
        backend.onInboundMessage(msg);

        verify(messageService).dispatch(argThat(d ->
                d.channelId().equals(channelId)
                && "human:+15551110000".equals(d.sender())
                && "I need help".equals(d.content())));
    }

    @Test
    void inboundMessageViaEvent_cdIWiring_routesToMessageService() throws Exception {
        // Verifies the CDI async event chain:
        //   InboundConnectorService.receive(msg) → Event<InboundMessage>.fireAsync()
        //     → @ObservesAsync ConnectorChannelBackend.onInboundMessage
        //
        // onInboundMessage returns CompletionStage<Void>; join() waits for observer completion
        // before asserting. ConnectorChannelBackend is in main sources — ArC registers its
        // @ObservesAsync method at build time, so fireAsync() reliably delivers the event.
        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, InboundConnectorTypes.SMS, "+15551110000",
                "+14155552671", "CDI wiring check", List.of(), Instant.now(), Map.of(), null);

        // 2 s: the observer does a single map lookup and one method call; generous budget for CI.
        inboundMessageEvent.fireAsync(msg).toCompletableFuture().get(2, TimeUnit.SECONDS);

        verify(messageService).dispatch(argThat(d ->
                d.channelId().equals(channelId)
                && "human:+15551110000".equals(d.sender())
                && "CDI wiring check".equals(d.content())));
    }

    @Test
    void unknownSender_noChannelBound_discardCounterIncremented() {
        double before = backend.discardedCount(InboundConnectorIds.TWILIO_SMS);

        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, InboundConnectorTypes.SMS, "+99999",
                "+14155552671", "hello", List.of(), Instant.now(), Map.of(), null);
        backend.onInboundMessage(msg);

        verify(messageService, never()).dispatch(any());
        assertThat(backend.discardedCount(InboundConnectorIds.TWILIO_SMS)).isGreaterThan(before);
    }

    @Test
    void fanOut_sendsViaConnectorService() {
        // Cache already populated by @BeforeEach → gateway.initChannel() → @Observes (sync).
        OutboundMessage outbound = new OutboundMessage(UUID.randomUUID(), "agent",
                MessageType.RESPONSE, "We can help", null, null, ActorType.AGENT, null, null);
        gateway.fanOut(channelId, "sms-alice", outbound);

        // timeout required — ChannelGateway.fanOut() dispatches backend.post() on a virtual thread.
        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService, timeout(1000).atLeastOnce()).send(eq("twilio-sms"), captor.capture());
        assertThat(captor.getValue().destination()).isEqualTo("+15551110000");
        assertThat(captor.getValue().body()).isEqualTo("We can help");
    }

    @Test
    void duplicateBinding_throws() {
        assertThatThrownBy(() ->
            channelService.create(ChannelCreateRequest.builder("sms-bob")
                .description("Bob's channel")
                .inboundConnectorId(InboundConnectorIds.TWILIO_SMS)
                .externalKey("+15551110000")   // same key as alice — should conflict
                .outboundConnectorId("twilio-sms")
                .outboundDestination("+15551110000")
                .build())
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Connector binding already exists");
    }
}

package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.connectors.ConnectorMessage;
import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.testing.InMemoryChannelBindingStore;
import io.casehub.qhorus.testing.InMemoryChannelStore;
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

    private UUID channelId;

    @BeforeEach
    void setUp() {
        channelStore.clear();
        channelBindingStore.clear();

        Channel ch = channelService.create(new ChannelCreateRequest(
                "sms-alice", "Alice's SMS conversation", ChannelSemantic.APPEND,
                null, null, null, null, null, null,
                InboundConnectorIds.TWILIO_SMS, "+15551110000", "twilio-sms", "+15551110000"));
        channelId = ch.id;
        // initChannel fires @Observes ChannelInitialisedEvent synchronously —
        // ConnectorChannelBackend.onChannelInitialised populates cache before setUp returns.
        gateway.initChannel(ch.id, new ChannelRef(ch.id, ch.name));
    }

    @AfterEach
    void tearDown() {
        gateway.closeChannel(channelId, new ChannelRef(channelId, "sms-alice"));
    }

    @Test
    void inboundMessage_routesToMessageService() {
        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, "+15551110000",
                "+14155552671", "I need help", Instant.now(), Map.of());

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
        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, "+15551110000",
                "+14155552671", "CDI wiring check", Instant.now(), Map.of());

        inboundMessageEvent.fireAsync(msg).toCompletableFuture().get(5, TimeUnit.SECONDS);

        verify(messageService).dispatch(argThat(d ->
                d.channelId().equals(channelId)
                && "human:+15551110000".equals(d.sender())
                && "CDI wiring check".equals(d.content())));
    }

    @Test
    void unknownSender_noChannelBound_discardCounterIncremented() {
        double before = backend.discardedCount(InboundConnectorIds.TWILIO_SMS);

        InboundMessage msg = new InboundMessage(InboundConnectorIds.TWILIO_SMS, "+99999",
                "+14155552671", "hello", Instant.now(), Map.of());
        backend.onInboundMessage(msg);

        verify(messageService, never()).dispatch(any());
        assertThat(backend.discardedCount(InboundConnectorIds.TWILIO_SMS)).isGreaterThan(before);
    }

    @Test
    void fanOut_sendsViaConnectorService() {
        // Cache already populated by @BeforeEach → gateway.initChannel() → @Observes (sync).
        OutboundMessage outbound = new OutboundMessage(UUID.randomUUID(), "agent",
                MessageType.RESPONSE, "We can help", null, null, ActorType.AGENT);
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
            channelService.create(new ChannelCreateRequest(
                "sms-bob", "Bob's channel", ChannelSemantic.APPEND,
                null, null, null, null, null, null,
                InboundConnectorIds.TWILIO_SMS, "+15551110000",   // same key as alice — should conflict
                "twilio-sms", "+15551110000"))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Connector binding already exists");
    }
}

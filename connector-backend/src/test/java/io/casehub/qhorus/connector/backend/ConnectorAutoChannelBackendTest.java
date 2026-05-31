package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.casehub.qhorus.testing.InMemoryChannelBindingStore;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ConnectorAutoChannelBackendTest {

    @Inject ConnectorChannelBackend backend;
    @Inject ChannelService channelService;
    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryChannelBindingStore channelBindingStore;

    @InjectMock MessageService messageService;
    @InjectMock ConnectorService connectorService;
    @InjectMock AutoChannelPolicy autoChannelPolicy;

    private static final String SENDER = "+447911000099";
    private static final String CONNECTOR = InboundConnectorIds.TWILIO_SMS;

    @BeforeEach
    void setUp() {
        channelStore.clear();
        channelBindingStore.clear();
        // Reset Mockito invocation history so verify() counts are per-test, not cumulative.
        Mockito.clearInvocations(messageService, connectorService, autoChannelPolicy);
    }

    @AfterEach
    void tearDown() {
        channelStore.scan(ChannelQuery.all()).forEach(ch ->
                gateway.closeChannel(ch.id, new ChannelRef(ch.id, ch.name)));
    }

    private InboundMessage smsMsg(String sender, String content) {
        return new InboundMessage(CONNECTOR, sender, "+14155550000", content, Instant.now(), Map.of());
    }

    private AutoChannelSpec smsSpec(String sender) {
        return new AutoChannelSpec(
                "connector/" + CONNECTOR + "/" + sender,
                "Auto-created on first contact via " + CONNECTOR + " from " + sender,
                ChannelSemantic.APPEND,
                null,
                "twilio-sms",
                sender);
    }

    @Test
    void firstContact_policyEnabled_channelCreatedAndMessageRouted() {
        when(autoChannelPolicy.onFirstContact(any(), eq(SENDER)))
                .thenReturn(Optional.of(smsSpec(SENDER)));
        double autoCreatedBefore = backend.autoCreatedCount(CONNECTOR);

        backend.onInboundMessage(smsMsg(SENDER, "hello"));

        assertThat(channelBindingStore.findByKey(CONNECTOR, SENDER)).isPresent();
        assertThat(channelStore.scan(ChannelQuery.all()).get(0).autoCreated).isTrue();
        verify(messageService).dispatch(argThat(d ->
                ("human:" + SENDER).equals(d.sender()) && "hello".equals(d.content())));
        assertThat(backend.autoCreatedCount(CONNECTOR)).isEqualTo(autoCreatedBefore + 1.0);
    }

    @Test
    void secondMessage_sameSender_reusesChannel_noNewChannelCreated() {
        when(autoChannelPolicy.onFirstContact(any(), eq(SENDER)))
                .thenReturn(Optional.of(smsSpec(SENDER)));
        double autoCreatedBefore = backend.autoCreatedCount(CONNECTOR);

        // First message — auto-creates, triggers initChannel via tryAutoCreate
        backend.onInboundMessage(smsMsg(SENDER, "first"));

        // Second message — findByConnectorKey finds the existing channel, no auto-create
        backend.onInboundMessage(smsMsg(SENDER, "second"));

        assertThat(channelBindingStore.findAll()).hasSize(1);
        // Only one new channel auto-created across both messages
        assertThat(backend.autoCreatedCount(CONNECTOR)).isEqualTo(autoCreatedBefore + 1.0);
        // 2 content messages × 2 dispatches each (message + normaliser telemetry EVENT per receiveHumanMessage)
        verify(messageService, times(4)).dispatch(any());
    }

    @Test
    void policyDisabled_returnsEmpty_messageDiscarded() {
        when(autoChannelPolicy.onFirstContact(any(), any())).thenReturn(Optional.empty());
        double before = backend.discardedCount(CONNECTOR);

        backend.onInboundMessage(smsMsg(SENDER, "hello"));

        assertThat(backend.discardedCount(CONNECTOR)).isGreaterThan(before);
        verify(messageService, never()).dispatch(any());
        assertThat(channelStore.scan(ChannelQuery.all())).isEmpty();
    }

    @Test
    void afterAutoCreate_fanOut_sendsToSenderPhone() {
        when(autoChannelPolicy.onFirstContact(any(), eq(SENDER)))
                .thenReturn(Optional.of(smsSpec(SENDER)));

        backend.onInboundMessage(smsMsg(SENDER, "hello"));

        Channel ch = channelStore.scan(ChannelQuery.all()).get(0);
        OutboundMessage reply = new OutboundMessage(UUID.randomUUID(), "agent",
                MessageType.RESPONSE, "reply text", null, null, ActorType.AGENT);
        gateway.fanOut(ch.id, ch.name, reply);

        ArgumentCaptor<ConnectorMessage> captor = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(connectorService, timeout(1000).atLeastOnce()).send(eq("twilio-sms"), captor.capture());
        assertThat(captor.getValue().destination()).isEqualTo(SENDER);
        assertThat(captor.getValue().body()).isEqualTo("reply text");
    }

    @Test
    void concurrentFirstContact_oneBindingCreated_bothMessagesDelivered() throws Exception {
        when(autoChannelPolicy.onFirstContact(any(), eq(SENDER)))
                .thenReturn(Optional.of(smsSpec(SENDER)));
        double autoCreatedBefore = backend.autoCreatedCount(CONNECTOR);

        InboundMessage msg1 = smsMsg(SENDER, "first");
        InboundMessage msg2 = smsMsg(SENDER, "second");

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(
                () -> backend.onInboundMessage(msg1));
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(
                () -> backend.onInboundMessage(msg2));
        CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);

        // Exactly one binding (unique constraint enforced by InMemoryChannelBindingStore)
        assertThat(channelBindingStore.findByKey(CONNECTOR, SENDER)).isPresent();
        // Both messages dispatched (2 messages × 2 dispatches each = message + normaliser EVENT)
        verify(messageService, times(4)).dispatch(any());
        // Only winner increments the auto-created counter
        assertThat(backend.autoCreatedCount(CONNECTOR)).isEqualTo(autoCreatedBefore + 1.0);
    }
}

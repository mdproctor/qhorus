package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.casehub.connectors.ConnectorService;
import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.persistence.memory.InMemoryChannelBindingStore;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Isolated test for concurrent first-contact auto-channel creation.
 * Kept in its own class to ensure the Prometheus counter starts clean.
 * See qhorus#248 for the root cause analysis.
 */
@QuarkusTest
class ConcurrentAutoChannelTest {

    @Inject ConnectorChannelBackend backend;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryChannelBindingStore channelBindingStore;

    @InjectMock MessageService messageService;
    @InjectMock ConnectorService connectorService;
    @InjectMock AutoChannelPolicy autoChannelPolicy;
    @InjectMock CurrentPrincipal currentPrincipal;

    private static final String SENDER = "+447911123456";
    private static final String CONNECTOR = InboundConnectorIds.TWILIO_SMS;

    @BeforeEach
    void setUp() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        channelStore.clear();
        channelBindingStore.clear();
    }

    @Test
    void concurrentFirstContact_oneBindingCreated() throws Exception {
        // Channel name uses sanitised sender segment — matches real ConfiguredAutoChannelPolicy output.
        String senderSegment = ConfiguredAutoChannelPolicy.sanitiseSegment(SENDER);
        when(autoChannelPolicy.onFirstContact(any(), eq(SENDER)))
                .thenReturn(Optional.of(new AutoChannelSpec(
                        "connector/" + CONNECTOR + "/" + senderSegment,
                        "Auto-created on first contact via " + CONNECTOR + " from " + SENDER,
                        ChannelSemantic.APPEND,
                        null, null,
                        "twilio-sms",
                        SENDER)));

        double autoCreatedBefore = backend.autoCreatedCount(CONNECTOR);

        InboundMessage msg1 = new InboundMessage(CONNECTOR, InboundConnectorTypes.SMS, SENDER, "+14155550000", "first", List.of(), Instant.now(), Map.of(), null);
        InboundMessage msg2 = new InboundMessage(CONNECTOR, InboundConnectorTypes.SMS, SENDER, "+14155550000", "second", List.of(), Instant.now(), Map.of(), null);

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> backend.onInboundMessage(msg1));
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> backend.onInboundMessage(msg2));
        CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);

        // Exactly one binding created — the unique constraint was enforced
        assertThat(channelBindingStore.findByKey(CONNECTOR, SENDER)).isPresent();
        // Only one winner increments the auto-created counter (race loser recovers)
        assertThat(backend.autoCreatedCount(CONNECTOR)).isEqualTo(autoCreatedBefore + 1.0);
        // Note: verify(messageService, times(4)).dispatch(any()) is omitted —
        // Mockito invocation recording is not thread-safe for concurrent callers. Refs qhorus#248.
    }
}

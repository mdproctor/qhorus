package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Verifies CDI wiring of {@link ConnectorQhorusMeshBridge} against real
 * {@link ChannelService} (backed by {@link InMemoryChannelStore}).
 *
 * <p>{@link MessageService} is mocked to assert dispatch arguments without
 * requiring a fully committed transaction. {@link ManagedExecutor} is mocked
 * with a synchronous runner to eliminate the async race at assertion time.
 *
 * <p>The delivery channel name is configured via
 * {@code casehub.qhorus.connector-backend.delivery-channel=connector-audit}
 * in {@code test/resources/application.properties}.
 */
@QuarkusTest
class ConnectorMeshBridgeIntegrationTest {

    @Inject ConnectorQhorusMeshBridge bridge;
    @Inject ChannelService channelService;
    @Inject InMemoryChannelStore channelStore;

    @InjectMock MessageService messageService;
    @InjectMock ManagedExecutor executor;
    @InjectMock CurrentPrincipal currentPrincipal;

    @BeforeEach
    void setUp() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        Mockito.when(messageService.dispatch(any())).thenReturn(null);

        // Synchronous executor — task runs on the test thread before verify() is called.
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());

        channelStore.clear();
        bridge.clearCache();
    }

    @AfterEach
    void tearDown() {
        channelStore.clear();
        bridge.clearCache();
    }

    @Test
    void channelPresent_dispatchesStatus_correctFields() {
        createDeliveryChannel();

        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", "Hello");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        final MessageDispatch dispatch = captor.getValue();

        assertThat(dispatch.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatch.sender()).isEqualTo("system:connector:slack");
        assertThat(dispatch.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(dispatch.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(dispatch.content()).isEqualTo("Delivered via slack: Hello");
        assertThat(dispatch.content()).doesNotContain("hooks.slack.com");
    }

    @Test
    void channelAbsent_noDispatch() {
        // No channel created — findByName returns empty.
        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", "Hello");

        verify(messageService, never()).dispatch(any());
    }

    @Test
    void channelId_matchesCreatedChannel() {
        final UUID expectedId = createDeliveryChannel();

        bridge.notifyDelivered("slack", "dest", "Hi");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().channelId()).isEqualTo(expectedId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createDeliveryChannel() {
        return channelService.create(ChannelCreateRequest.builder("connector-audit")
                .description("Connector delivery audit")
                .build()).id;
    }
}

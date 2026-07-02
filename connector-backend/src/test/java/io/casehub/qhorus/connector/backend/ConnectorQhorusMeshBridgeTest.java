package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;

class ConnectorQhorusMeshBridgeTest {

    private ChannelService channelService;
    private MessageService messageService;
    private CurrentPrincipal currentPrincipal;
    private ManagedExecutor executor;
    private ConnectorQhorusMeshBridge bridge;

    private static final String DEFAULT_TENANCY = "tenant-default";
    private static final UUID CHANNEL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        messageService = mock(MessageService.class);
        currentPrincipal = mock(CurrentPrincipal.class);
        executor = mock(ManagedExecutor.class);

        // Synchronous executor — runs the Runnable inline before returning.
        // Eliminates the async race without needing Awaitility.
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());

        bridge = new ConnectorQhorusMeshBridge(channelService, messageService, currentPrincipal, executor);
        bridge.deliveryChannelName = "connector-audit";

        lenient().when(currentPrincipal.tenancyId()).thenReturn(DEFAULT_TENANCY);
        lenient().when(messageService.dispatch(any())).thenReturn(dummyResult());
    }

    @Test
    void nullConnectorId_noOp_spiContractHonoured() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));

        assertThatCode(() -> bridge.notifyDelivered(null, "dest", "Hello"))
                .doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void blankDeliveryChannelName_noOp() {
        bridge.deliveryChannelName = "";

        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", "Hello");

        verify(channelService, never()).findByName(any());
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void channelNotFound_noDispatch() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.empty());

        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", "Hello");

        verify(channelService).findByName("connector-audit");
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void happyPath_dispatchesStatus_correctFields() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));

        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", "Hello");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        final MessageDispatch dispatch = captor.getValue();

        assertThat(dispatch.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(dispatch.sender()).isEqualTo("system:connector:slack");
        assertThat(dispatch.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatch.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(dispatch.tenancyId()).isEqualTo(DEFAULT_TENANCY);
        assertThat(dispatch.content()).isEqualTo("Delivered via slack: Hello");
    }

    @Test
    void nullContent_dispatchesWithEmptyBody() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));

        bridge.notifyDelivered("slack", "https://hooks.slack.com/services/x", null);

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("Delivered via slack: ");
    }

    @Test
    void destinationNotIncludedInContent() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));
        final String webhookUrl = "https://hooks.slack.com/services/T000/B000/SECRET";

        bridge.notifyDelivered("slack", webhookUrl, "Hello");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().content()).doesNotContain(webhookUrl);
    }

    @Test
    void senderEncodesConnectorId() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));

        bridge.notifyDelivered("teams", "https://outlook.office.com/webhook/x", "Hi");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo("system:connector:teams");
    }

    @Test
    void cacheHit_secondCallSameTenant_findByNameCalledOnce() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));

        bridge.notifyDelivered("slack", "dest", "First");
        bridge.notifyDelivered("slack", "dest", "Second");

        verify(channelService, times(1)).findByName("connector-audit");
        verify(messageService, times(2)).dispatch(any());
    }

    @Test
    void differentTenants_separateCacheLookups_separateDispatches() {
        final Channel channelA = channel();
        final UUID    idA      = channelA.id();
        final Channel channelB = Channel.builder("connector-audit").id(UUID.randomUUID()).build();

        when(currentPrincipal.tenancyId()).thenReturn("tenant-a").thenReturn("tenant-b");
        when(channelService.findByName("connector-audit"))
                .thenReturn(Optional.of(channelA))
                .thenReturn(Optional.of(channelB));

        bridge.notifyDelivered("slack", "dest", "Hello");
        bridge.notifyDelivered("slack", "dest", "World");

        verify(channelService, times(2)).findByName("connector-audit");

        final ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, times(2)).dispatch(captor.capture());
        assertThat(captor.getAllValues().get(0).channelId()).isEqualTo(idA);
        assertThat(captor.getAllValues().get(1).channelId()).isEqualTo(channelB.id());
        assertThat(captor.getAllValues().get(0).tenancyId()).isEqualTo("tenant-a");
        assertThat(captor.getAllValues().get(1).tenancyId()).isEqualTo("tenant-b");
    }

    @Test
    void channelMissNotCached_retryOnNextCall() {
        // First call: channel absent. Second call: channel present.
        // computeIfAbsent does not cache null — so second call retries.
        when(channelService.findByName("connector-audit"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(channel()));

        bridge.notifyDelivered("slack", "dest", "First");  // miss → no dispatch
        bridge.notifyDelivered("slack", "dest", "Second"); // retry → dispatches

        verify(channelService, times(2)).findByName("connector-audit");
        verify(messageService, times(1)).dispatch(any());
    }

    @Test
    void findByNameThrows_swallowed_spiContractHonoured() {
        when(channelService.findByName(any())).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> bridge.notifyDelivered("slack", "dest", "Hello"))
                .doesNotThrowAnyException();
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void dispatchThrows_swallowed_spiContractHonoured() {
        when(channelService.findByName("connector-audit")).thenReturn(Optional.of(channel()));
        when(messageService.dispatch(any())).thenThrow(new RuntimeException("Dispatch failed"));

        assertThatCode(() -> bridge.notifyDelivered("slack", "dest", "Hello"))
                .doesNotThrowAnyException();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Channel channel() {
        return Channel.builder("connector-audit").id(CHANNEL_ID).build();
    }

    private static DispatchResult dummyResult() {
        return new DispatchResult(1L, UUID.randomUUID(), "system:connector:slack",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, List.of());
    }
}

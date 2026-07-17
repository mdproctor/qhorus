package io.casehub.qhorus.notification.bridge;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationBridgeObserverTest {

    private CommitmentStore commitmentStore;
    private NotificationStore notificationStore;
    private NotificationBridgeObserver observer;

    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final String CHANNEL_NAME = "test-channel";
    private static final String TENANCY_ID = "tenant-1";
    private static final String CORRELATION_ID = UUID.randomUUID().toString();
    private static final String REQUESTER = "agent-requester";
    private static final String OBLIGOR = "agent-obligor";

    @BeforeEach
    void setUp() {
        commitmentStore = mock(CommitmentStore.class);
        notificationStore = mock(NotificationStore.class);
        observer = new NotificationBridgeObserver(commitmentStore, notificationStore);
    }

    @Test
    void command_with_obligor_notifies_obligor() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, OBLIGOR)));

        observer.onMessage(event(MessageType.COMMAND, REQUESTER, "Do this task"));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());
        NotificationInput input = captor.getValue();

        assertThat(input.userId()).isEqualTo(OBLIGOR);
        assertThat(input.tenancyId()).isEqualTo(TENANCY_ID);
        assertThat(input.category()).isEqualTo(NotificationCategories.OBLIGATION_ASSIGNED);
        assertThat(input.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(input.title()).contains(CHANNEL_NAME);
        assertThat(input.source().entityType()).isEqualTo("channel");
        assertThat(input.source().entityId()).isEqualTo(CHANNEL_ID.toString());
    }

    @Test
    void command_without_commitment_skips_notification() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.empty());

        observer.onMessage(event(MessageType.COMMAND, REQUESTER, "Do this"));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void command_with_blank_obligor_skips_notification() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, "")));

        observer.onMessage(event(MessageType.COMMAND, REQUESTER, "Do this"));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void done_notifies_requester() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, OBLIGOR)));

        observer.onMessage(event(MessageType.DONE, OBLIGOR, "Task complete"));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());
        NotificationInput input = captor.getValue();

        assertThat(input.userId()).isEqualTo(REQUESTER);
        assertThat(input.category()).isEqualTo(NotificationCategories.OBLIGATION_FULFILLED);
        assertThat(input.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(input.title()).contains("completed");
    }

    @Test
    void failure_notifies_requester_with_warning_severity() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, OBLIGOR)));

        observer.onMessage(event(MessageType.FAILURE, OBLIGOR, "Could not complete"));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());
        NotificationInput input = captor.getValue();

        assertThat(input.userId()).isEqualTo(REQUESTER);
        assertThat(input.category()).isEqualTo(NotificationCategories.OBLIGATION_FAILED);
        assertThat(input.severity()).isEqualTo(NotificationSeverity.WARNING);
    }

    @Test
    void done_from_requester_to_self_skips_notification() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, OBLIGOR)));

        observer.onMessage(event(MessageType.DONE, REQUESTER, "Self-resolved"));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void status_message_skips_notification() {
        observer.onMessage(event(MessageType.STATUS, OBLIGOR, "Progress update"));

        verify(commitmentStore, never()).findByCorrelationId(any());
        verify(notificationStore, never()).store(any());
    }

    @Test
    void event_message_skips_notification() {
        observer.onMessage(eventWithNullContent(MessageType.EVENT, "system:telemetry"));

        verify(commitmentStore, never()).findByCorrelationId(any());
        verify(notificationStore, never()).store(any());
    }

    @Test
    void null_correlationId_skips_all_processing() {
        var event = new MessageReceivedEvent(
                1L, CHANNEL_NAME, CHANNEL_ID, TENANCY_ID,
                MessageType.COMMAND, REQUESTER, null,
                Instant.now(), "Do this", null);

        observer.onMessage(event);

        verify(commitmentStore, never()).findByCorrelationId(any());
        verify(notificationStore, never()).store(any());
    }

    @Test
    void scope_is_local() {
        assertThat(observer.scope()).isEqualTo(io.casehub.qhorus.api.gateway.MessageObserver.Scope.LOCAL);
    }

    @Test
    void store_failure_is_non_fatal() {
        when(commitmentStore.findByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(commitment(REQUESTER, OBLIGOR)));
        doThrow(new RuntimeException("DB down")).when(notificationStore).store(any());

        observer.onMessage(event(MessageType.COMMAND, REQUESTER, "Do this"));
        // no exception propagated
    }

    @Test
    void truncate_long_content() {
        assertThat(NotificationBridgeObserver.truncate(null, 10)).isNull();
        assertThat(NotificationBridgeObserver.truncate("short", 10)).isEqualTo("short");
        assertThat(NotificationBridgeObserver.truncate("a".repeat(300), 200)).hasSize(200);
    }

    private MessageReceivedEvent event(MessageType type, String sender, String content) {
        return new MessageReceivedEvent(
                1L, CHANNEL_NAME, CHANNEL_ID, TENANCY_ID,
                type, sender, CORRELATION_ID,
                Instant.now(), content, null);
    }

    private MessageReceivedEvent eventWithNullContent(MessageType type, String sender) {
        return new MessageReceivedEvent(
                1L, CHANNEL_NAME, CHANNEL_ID, TENANCY_ID,
                type, sender, CORRELATION_ID,
                Instant.now(), null, null);
    }

    private Commitment commitment(String requester, String obligor) {
        return Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .channelId(CHANNEL_ID)
                .messageType(MessageType.COMMAND)
                .requester(requester)
                .obligor(obligor)
                .state(CommitmentState.OPEN)
                .tenancyId(TENANCY_ID)
                .createdAt(Instant.now())
                .build();
    }
}

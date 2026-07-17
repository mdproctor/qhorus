package io.casehub.qhorus.notification.bridge;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
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

class CommitmentEventNotifierTest {

    private CommitmentStore commitmentStore;
    private NotificationStore notificationStore;
    private CommitmentEventNotifier notifier;

    private static final UUID COMMITMENT_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final String CORRELATION_ID = UUID.randomUUID().toString();
    private static final String TENANCY_ID = "tenant-1";
    private static final String REQUESTER = "agent-requester";
    private static final String OBLIGOR = "agent-obligor";

    @BeforeEach
    void setUp() {
        commitmentStore = mock(CommitmentStore.class);
        notificationStore = mock(NotificationStore.class);
        notifier = new CommitmentEventNotifier(commitmentStore, notificationStore);

        when(commitmentStore.findById(COMMITMENT_ID))
                .thenReturn(Optional.of(commitment()));
    }

    @Test
    void declined_notifies_requester() {
        notifier.onDeclined(new CommitmentDeclinedEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, REQUESTER));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());
        NotificationInput input = captor.getValue();

        assertThat(input.userId()).isEqualTo(REQUESTER);
        assertThat(input.tenancyId()).isEqualTo(TENANCY_ID);
        assertThat(input.category()).isEqualTo(NotificationCategories.OBLIGATION_DECLINED);
        assertThat(input.severity()).isEqualTo(NotificationSeverity.WARNING);
        assertThat(input.title()).contains("declined").contains(OBLIGOR);
        assertThat(input.source().entityType()).isEqualTo("channel");
        assertThat(input.source().entityId()).isEqualTo(CHANNEL_ID.toString());
        assertThat(input.source().actorId()).isEqualTo(OBLIGOR);
    }

    @Test
    void declined_with_null_requester_skips() {
        notifier.onDeclined(new CommitmentDeclinedEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, null));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void declined_with_blank_requester_skips() {
        notifier.onDeclined(new CommitmentDeclinedEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, ""));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void expired_notifies_requester_with_urgent_severity() {
        notifier.onExpired(new CommitmentExpiredEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, REQUESTER,
                Instant.now().minusSeconds(60)));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());
        NotificationInput input = captor.getValue();

        assertThat(input.userId()).isEqualTo(REQUESTER);
        assertThat(input.category()).isEqualTo(NotificationCategories.OBLIGATION_EXPIRED);
        assertThat(input.severity()).isEqualTo(NotificationSeverity.URGENT);
        assertThat(input.title()).contains("expired");
    }

    @Test
    void expired_with_null_obligor_uses_unassigned_label() {
        notifier.onExpired(new CommitmentExpiredEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, null, REQUESTER,
                Instant.now()));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());

        assertThat(captor.getValue().title()).contains("unassigned");
    }

    @Test
    void expired_with_null_requester_skips() {
        notifier.onExpired(new CommitmentExpiredEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, null,
                Instant.now()));

        verify(notificationStore, never()).store(any());
    }

    @Test
    void declined_uses_default_tenancy_when_commitment_not_found() {
        when(commitmentStore.findById(COMMITMENT_ID)).thenReturn(Optional.empty());

        notifier.onDeclined(new CommitmentDeclinedEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, REQUESTER));

        var captor = ArgumentCaptor.forClass(NotificationInput.class);
        verify(notificationStore).store(captor.capture());

        assertThat(captor.getValue().tenancyId()).isEqualTo("DEFAULT");
    }

    @Test
    void store_failure_is_non_fatal() {
        doThrow(new RuntimeException("DB down")).when(notificationStore).store(any());

        notifier.onDeclined(new CommitmentDeclinedEvent(
                COMMITMENT_ID, CORRELATION_ID, CHANNEL_ID, OBLIGOR, REQUESTER));
        // no exception propagated
    }

    private Commitment commitment() {
        return Commitment.builder()
                .id(COMMITMENT_ID)
                .correlationId(CORRELATION_ID)
                .channelId(CHANNEL_ID)
                .messageType(MessageType.COMMAND)
                .requester(REQUESTER)
                .obligor(OBLIGOR)
                .state(CommitmentState.DECLINED)
                .tenancyId(TENANCY_ID)
                .createdAt(Instant.now())
                .build();
    }
}

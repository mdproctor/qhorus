package io.quarkiverse.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.PendingReplyStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests verifying that MessageService delegates all PendingReply
 * operations to PendingReplyStore — no direct Panache static calls.
 */
@QuarkusTest
class MessageServicePendingReplyTest {

    @InjectMock
    PendingReplyStore pendingReplyStore;

    @Inject
    MessageService messageService;

    // --- registerPendingReply: new entry ---

    @Test
    void registerPendingReply_newEntry_savesNewRow() {
        String corrId = "corr-new-" + UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);

        when(pendingReplyStore.findByCorrelationId(corrId)).thenReturn(Optional.empty());
        when(pendingReplyStore.save(any(PendingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        messageService.registerPendingReply(corrId, channelId, instanceId, expiresAt);

        verify(pendingReplyStore).findByCorrelationId(corrId);
        verify(pendingReplyStore).save(argThat(pr -> pr.correlationId.equals(corrId) &&
                pr.channelId.equals(channelId) &&
                pr.instanceId.equals(instanceId) &&
                pr.expiresAt.equals(expiresAt)));
    }

    // --- registerPendingReply: existing entry — upsert updates expiresAt ---

    @Test
    void registerPendingReply_existingEntry_updatesExpiresAtAndSaves() {
        String corrId = "corr-existing-" + UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        Instant originalExpiry = Instant.now().plusSeconds(30);
        Instant extendedExpiry = Instant.now().plusSeconds(3600);

        PendingReply existing = new PendingReply();
        existing.correlationId = corrId;
        existing.channelId = channelId;
        existing.expiresAt = originalExpiry;

        when(pendingReplyStore.findByCorrelationId(corrId)).thenReturn(Optional.of(existing));
        when(pendingReplyStore.save(any(PendingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        messageService.registerPendingReply(corrId, channelId, null, extendedExpiry);

        verify(pendingReplyStore).findByCorrelationId(corrId);
        verify(pendingReplyStore).save(argThat(pr -> pr.correlationId.equals(corrId) &&
                pr.expiresAt.equals(extendedExpiry)));
        // must NOT create a second row
        verify(pendingReplyStore, times(1)).save(any());
    }

    // --- deletePendingReply ---

    @Test
    void deletePendingReply_delegatesToStore() {
        String corrId = "corr-del-" + UUID.randomUUID();

        messageService.deletePendingReply(corrId);

        verify(pendingReplyStore).deleteByCorrelationId(corrId);
        verifyNoMoreInteractions(pendingReplyStore);
    }

    // --- pendingReplyExists ---

    @Test
    void pendingReplyExists_delegatesToStore_returnsTrue() {
        String corrId = "corr-exists-true-" + UUID.randomUUID();
        when(pendingReplyStore.existsByCorrelationId(corrId)).thenReturn(true);

        assertTrue(messageService.pendingReplyExists(corrId));
        verify(pendingReplyStore).existsByCorrelationId(corrId);
    }

    @Test
    void pendingReplyExists_delegatesToStore_returnsFalse() {
        String corrId = "corr-exists-false-" + UUID.randomUUID();
        when(pendingReplyStore.existsByCorrelationId(corrId)).thenReturn(false);

        assertFalse(messageService.pendingReplyExists(corrId));
        verify(pendingReplyStore).existsByCorrelationId(corrId);
    }
}

package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster.ChannelActivityEvent;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;

/**
 * Verifies that {@link ChannelActivityBroadcaster#broadcast} is called after commit
 * in both the normal dispatch path and the LAST_WRITE overwrite path.
 *
 * <p>Uses {@code @InjectMock} to replace the {@code NoOpChannelActivityBroadcaster}
 * {@code @DefaultBean} with a Mockito spy. Dispatch runs inside
 * {@code QuarkusTransaction.requiringNew()} so JTA afterCompletion synchronisations fire.
 *
 * <p>Refs #162.
 */
@QuarkusTest
class ChannelActivityBroadcastIntegrationTest {

    @Inject MessageService messageService;
    @Inject ChannelStore channelStore;
    @Inject ChannelGateway channelGateway;

    @InjectMock ChannelActivityBroadcaster broadcaster;

    @BeforeEach
    void resetMock() {
        reset(broadcaster);
    }

    // ── Normal path ──────────────────────────────────────────────────────────

    @Test
    void dispatch_normalPath_broadcastsAfterCommit() {
        String channelName = "bcast-normal-" + UUID.randomUUID();
        UUID channelId = createAndCommitChannel(channelName, ChannelSemantic.APPEND);

        DispatchResult[] result = {null};
        QuarkusTransaction.requiringNew().run(() ->
            result[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("agent-1")
                    .type(MessageType.STATUS).content("hello")
                    .actorType(ActorType.AGENT).build()));

        ArgumentCaptor<ChannelActivityEvent> captor =
                ArgumentCaptor.forClass(ChannelActivityEvent.class);
        verify(broadcaster).broadcast(captor.capture());

        ChannelActivityEvent event = captor.getValue();
        assertThat(event.channelId()).isEqualTo(channelId);
        assertThat(event.channelName()).isEqualTo(channelName);
        assertThat(event.messageId()).isEqualTo(result[0].messageId());
    }

    // ── LAST_WRITE overwrite path ────────────────────────────────────────────

    @Test
    void dispatch_lastWriteOverwrite_broadcastsAfterCommit() {
        String channelName = "bcast-lw-" + UUID.randomUUID();
        UUID channelId = createAndCommitChannel(channelName, ChannelSemantic.LAST_WRITE);

        // First dispatch — initial write (normal insert path)
        DispatchResult[] first = {null};
        QuarkusTransaction.requiringNew().run(() ->
            first[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("writer")
                    .type(MessageType.STATUS).content("v1")
                    .actorType(ActorType.AGENT).build()));

        // Verify broadcast fired for the initial write
        verify(broadcaster, times(1)).broadcast(any());
        reset(broadcaster);

        // Second dispatch — overwrite (LAST_WRITE same sender)
        DispatchResult[] second = {null};
        QuarkusTransaction.requiringNew().run(() ->
            second[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("writer")
                    .type(MessageType.STATUS).content("v2")
                    .actorType(ActorType.AGENT).build()));

        // Overwrite returns same message ID
        assertThat(second[0].messageId()).isEqualTo(first[0].messageId());

        // Broadcast fires for the overwrite path too
        ArgumentCaptor<ChannelActivityEvent> captor =
                ArgumentCaptor.forClass(ChannelActivityEvent.class);
        verify(broadcaster).broadcast(captor.capture());

        ChannelActivityEvent event = captor.getValue();
        assertThat(event.channelId()).isEqualTo(channelId);
        assertThat(event.channelName()).isEqualTo(channelName);
        assertThat(event.messageId()).isEqualTo(second[0].messageId());
    }

    // ── LAST_WRITE overwrite — fanOut fires ─────────────────────────────────

    /**
     * Verifies the pre-existing gap fix: the LAST_WRITE overwrite path now
     * calls {@code channelGateway.fanOut()}, which previously was skipped.
     * We verify indirectly via broadcast — if fanOut fires, the code path
     * that registers the afterCompletion sync (which calls both signal and
     * broadcast) has executed.
     */
    @Test
    void dispatch_lastWriteOverwrite_broadcastIncludesCorrectMessageId() {
        String channelName = "bcast-lw-fanout-" + UUID.randomUUID();
        UUID channelId = createAndCommitChannel(channelName, ChannelSemantic.LAST_WRITE);

        // Initial write
        QuarkusTransaction.requiringNew().run(() ->
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("writer")
                    .type(MessageType.STATUS).content("v1")
                    .actorType(ActorType.AGENT).build()));
        reset(broadcaster);

        // Overwrite
        DispatchResult[] overwrite = {null};
        QuarkusTransaction.requiringNew().run(() ->
            overwrite[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("writer")
                    .type(MessageType.STATUS).content("v2")
                    .actorType(ActorType.AGENT).build()));

        ArgumentCaptor<ChannelActivityEvent> captor =
                ArgumentCaptor.forClass(ChannelActivityEvent.class);
        verify(broadcaster).broadcast(captor.capture());

        // The messageId in the event is the saved (updated) message id
        assertThat(captor.getValue().messageId()).isEqualTo(overwrite[0].messageId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID createAndCommitChannel(String name, ChannelSemantic semantic) {
        UUID[] id = {null};
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelStore.put(Channel.builder(name)
                    .id(UUID.randomUUID()).semantic(semantic).build());
            id[0] = ch.id();
        });
        return id[0];
    }
}

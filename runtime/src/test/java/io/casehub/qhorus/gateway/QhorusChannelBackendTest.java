package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.QhorusChannelBackend;
import io.casehub.qhorus.runtime.message.MessageService;

class QhorusChannelBackendTest {

    MessageService messageService;
    QhorusChannelBackend backend;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        backend = new QhorusChannelBackend(messageService);
    }

    @Test
    void backendId_isQhorusInternal() {
        assertEquals("qhorus-internal", backend.backendId());
    }

    @Test
    void actorType_isAgent() {
        assertEquals(ActorType.AGENT, backend.actorType());
    }

    @Test
    void post_delegatesToMessageService() {
        UUID channelId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(channelId, "test-channel");
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.COMMAND, "do the thing", corrId, ActorType.AGENT);

        backend.post(ref, msg);

        verify(messageService).send(eq(channelId), eq("agent-a"), eq(MessageType.COMMAND),
                eq("do the thing"), eq(corrId.toString()), isNull(),
                isNull(), isNull(), eq(ActorType.AGENT));
    }

    @Test
    void post_nullCorrelationId_passesNullString() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "ch");
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.EVENT, "tool done", null, ActorType.AGENT);

        backend.post(ref, msg);

        verify(messageService).send(any(), any(), any(), any(), isNull(), isNull(),
                isNull(), isNull(), any());
    }

    @Test
    void open_and_close_areNoOps() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "ch");
        assertDoesNotThrow(() -> backend.open(ref, Map.of()));
        assertDoesNotThrow(() -> backend.close(ref));
    }
}

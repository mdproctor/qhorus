package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.QhorusChannelBackend;

class QhorusChannelBackendTest {

    QhorusChannelBackend backend;

    @BeforeEach
    void setUp() {
        backend = new QhorusChannelBackend();
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
    void post_isNoOp_doesNotThrow() {
        // post() is deliberately a no-op: fanOut() skips this backend and persistence
        // already happened via MessageService.dispatch() before fanOut is called.
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "test-channel");
        OutboundMessage msg = new OutboundMessage(UUID.randomUUID(), "agent-a",
                MessageType.COMMAND, "do the thing", UUID.randomUUID().toString(), null, ActorType.AGENT, null, null);

        assertDoesNotThrow(() -> backend.post(ref, msg));
    }

    @Test
    void open_and_close_areNoOps() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "ch");
        assertDoesNotThrow(() -> backend.open(ref, Map.of()));
        assertDoesNotThrow(() -> backend.close(ref));
    }
}

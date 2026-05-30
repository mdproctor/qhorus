package io.casehub.qhorus.runtime.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelGatewayInitialisedEventTest {

    @Inject
    ChannelGateway gateway;

    @Inject
    TestChannelInitialisedEventObserver observer;

    @BeforeEach
    void clearObserver() {
        observer.clear();
    }

    @Test
    void initChannel_fires_ChannelInitialisedEvent() {
        UUID id = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(id, "test-init-event");

        gateway.initChannel(id, ref);

        assertEquals(1, observer.events().size());
        ChannelInitialisedEvent event = observer.events().get(0);
        assertEquals(id, event.channelId());
        assertEquals("test-init-event", event.channelName());
        assertFalse(event.recovered(), "initChannel() fires recovered=false (not startup recovery)");
    }

    @Test
    void initChannel_withRecoveredTrue_firesEventWithRecoveredFlag() {
        UUID id = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(id, "test-recovery");

        gateway.initChannel(id, ref, true);

        assertEquals(1, observer.events().size());
        assertTrue(observer.events().get(0).recovered(), "initChannel(recovered=true) fires recovered=true");
    }

    @Test
    void initChannel_firesEvent_evenWhenCalledAgainForSameChannel() {
        UUID id = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(id, "test-repeat");

        gateway.initChannel(id, ref);
        observer.clear();
        gateway.initChannel(id, ref);

        assertEquals(1, observer.events().size());
    }
}

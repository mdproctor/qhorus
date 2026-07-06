package io.casehub.qhorus.runtime.gateway;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster.ChannelActivityEvent;

class NoOpChannelActivityBroadcasterTest {
    @Test
    void broadcast_doesNotThrow() {
        var broadcaster = new NoOpChannelActivityBroadcaster();
        broadcaster.broadcast(new ChannelActivityEvent(
                UUID.randomUUID(), "test-channel", 42L));
    }
}

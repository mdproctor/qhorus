package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DeliverySignalQueueTest {

    @Test
    void signal_wakesPollThread() throws InterruptedException {
        DeliverySignalQueue queue = new DeliverySignalQueue();
        UUID channelId = UUID.randomUUID();
        queue.signal(channelId);
        UUID polled = queue.poll(1, TimeUnit.SECONDS);
        assertThat(polled).isEqualTo(channelId);
    }

    @Test
    void drainTo_collectsMultipleSignals() throws InterruptedException {
        DeliverySignalQueue queue = new DeliverySignalQueue();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        queue.signal(a);
        queue.signal(b);
        UUID first = queue.poll(1, TimeUnit.SECONDS);
        List<UUID> rest = new ArrayList<>();
        queue.drainTo(rest);
        assertThat(first).isEqualTo(a);
        assertThat(rest).containsExactly(b);
    }

    @Test
    void poll_timeout_returnsNull() throws InterruptedException {
        DeliverySignalQueue queue = new DeliverySignalQueue();
        UUID polled = queue.poll(50, TimeUnit.MILLISECONDS);
        assertThat(polled).isNull();
    }
}

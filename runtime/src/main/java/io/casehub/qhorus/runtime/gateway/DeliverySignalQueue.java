package io.casehub.qhorus.runtime.gateway;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DeliverySignalQueue {

    private final LinkedBlockingDeque<UUID> queue = new LinkedBlockingDeque<>();

    public void signal(UUID channelId) {
        queue.offer(channelId);
    }

    public UUID poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int drainTo(Collection<? super UUID> c) {
        return queue.drainTo(c);
    }
}

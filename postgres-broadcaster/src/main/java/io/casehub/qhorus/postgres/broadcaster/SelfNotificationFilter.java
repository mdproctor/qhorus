package io.casehub.qhorus.postgres.broadcaster;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bounded set of recently-dispatched message IDs for self-notification filtering.
 *
 * <p>PostgreSQL NOTIFY delivers to all listeners, including the sender. This filter
 * lets the notification handler skip messages dispatched by this node — local delivery
 * already happened via {@code fanOut()}.
 *
 * <p>Thread-safe: dispatch threads call {@link #recordSent(Long)}, the Vert.x event
 * loop calls {@link #wasSentLocally(Long)}. {@code Collections.synchronizedSet()}
 * provides mutual exclusion; contention is low since dispatch throughput is bounded
 * by database write speed.
 *
 * <p>The filter is an optimization, not a correctness requirement. If a self-notification
 * slips through after eviction, {@code deliverRemote()} runs unnecessarily — backends
 * receive a redundant {@code post()} call, which is harmless.
 */
final class SelfNotificationFilter {

    private final Set<Long> recentIds;
    private final int maxSize;

    SelfNotificationFilter(int maxSize) {
        this.maxSize = maxSize;
        this.recentIds = Collections.synchronizedSet(new LinkedHashSet<>());
    }

    void recordSent(Long messageId) {
        synchronized (recentIds) {
            recentIds.add(messageId);
            if (recentIds.size() > maxSize) {
                Iterator<Long> it = recentIds.iterator();
                it.next();
                it.remove();
            }
        }
    }

    boolean wasSentLocally(Long messageId) {
        return recentIds.contains(messageId);
    }
}

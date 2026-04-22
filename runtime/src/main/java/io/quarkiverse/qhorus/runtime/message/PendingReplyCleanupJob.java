package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.store.PendingReplyStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job that deletes expired PendingReply rows — entries left by
 * agents that disconnected or crashed without completing their wait_for_reply.
 */
@ApplicationScoped
public class PendingReplyCleanupJob {

    @Inject
    PendingReplyStore pendingReplyStore;

    @Scheduled(every = "${quarkus.qhorus.cleanup.pending-reply-check-seconds}s")
    @Transactional
    public void cleanupExpired() {
        long deleted = pendingReplyStore.deleteExpiredBefore(Instant.now());
        if (deleted > 0) {
            Log.infof("PendingReply cleanup: deleted %d expired entries", deleted);
        }
    }
}

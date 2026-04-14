package io.quarkiverse.qhorus.runtime.message;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job that deletes expired PendingReply rows — entries left by
 * agents that disconnected or crashed without completing their wait_for_reply.
 */
@ApplicationScoped
public class PendingReplyCleanupJob {

    @Scheduled(every = "${quarkus.qhorus.cleanup.pending-reply-check-seconds}s")
    @Transactional
    public void cleanupExpired() {
        long deleted = PendingReply.delete("expiresAt < ?1", java.time.Instant.now());
        if (deleted > 0) {
            io.quarkus.logging.Log.infof("PendingReply cleanup: deleted %d expired entries", deleted);
        }
    }
}

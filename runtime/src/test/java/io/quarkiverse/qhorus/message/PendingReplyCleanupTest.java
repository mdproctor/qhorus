package io.quarkiverse.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.message.PendingReplyCleanupJob;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PendingReplyCleanupTest {

    @Inject
    PendingReplyCleanupJob cleanupJob;

    @Test
    @TestTransaction
    void cleanupDeletesExpiredPendingReplies() {
        PendingReply expired = new PendingReply();
        expired.correlationId = "expired-corr-" + UUID.randomUUID();
        expired.expiresAt = Instant.now().minusSeconds(60); // already expired
        expired.persist();

        cleanupJob.cleanupExpired();

        long remaining = PendingReply.count("correlationId", expired.correlationId);
        assertEquals(0, remaining, "expired PendingReply should be deleted by cleanup job");
    }

    @Test
    @TestTransaction
    void cleanupDoesNotDeleteFuturePendingReplies() {
        PendingReply future = new PendingReply();
        future.correlationId = "future-corr-" + UUID.randomUUID();
        future.expiresAt = Instant.now().plusSeconds(3600); // expires in 1 hour
        future.persist();

        cleanupJob.cleanupExpired();

        long remaining = PendingReply.count("correlationId", future.correlationId);
        assertEquals(1, remaining, "non-expired PendingReply should not be deleted");
    }

    @Test
    @TestTransaction
    void cleanupIsIdempotent() {
        PendingReply expired = new PendingReply();
        expired.correlationId = "idem-corr-" + UUID.randomUUID();
        expired.expiresAt = Instant.now().minusSeconds(10);
        expired.persist();

        cleanupJob.cleanupExpired();
        // Running again should not throw
        assertDoesNotThrow(() -> cleanupJob.cleanupExpired());
    }
}

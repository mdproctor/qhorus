package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.PendingReply;

class InMemoryPendingReplyStoreTest {

    private InMemoryPendingReplyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingReplyStore();
        store.clear();
    }

    @Test
    void saveAndFind_happyPath() {
        PendingReply pr = pendingReply("corr-1", Instant.now().plusSeconds(60));
        store.save(pr);
        Optional<PendingReply> found = store.findByCorrelationId("corr-1");
        assertTrue(found.isPresent());
        assertEquals("corr-1", found.get().correlationId);
    }

    @Test
    void save_assignsIdIfAbsent() {
        PendingReply pr = pendingReply("corr-2", Instant.now().plusSeconds(60));
        assertNull(pr.id);
        store.save(pr);
        assertNotNull(pr.id);
    }

    @Test
    void findByCorrelationId_notFound_returnsEmpty() {
        assertTrue(store.findByCorrelationId("nonexistent").isEmpty());
    }

    @Test
    void save_updatesExistingEntry() {
        PendingReply pr = pendingReply("corr-3", Instant.now().plusSeconds(60));
        store.save(pr);
        Instant newExpiry = Instant.now().plusSeconds(120);
        pr.expiresAt = newExpiry;
        store.save(pr);
        assertEquals(newExpiry, store.findByCorrelationId("corr-3").get().expiresAt);
    }

    @Test
    void deleteByCorrelationId_removesEntry() {
        store.save(pendingReply("corr-4", Instant.now().plusSeconds(60)));
        store.deleteByCorrelationId("corr-4");
        assertTrue(store.findByCorrelationId("corr-4").isEmpty());
    }

    @Test
    void deleteByCorrelationId_nonexistent_noError() {
        assertDoesNotThrow(() -> store.deleteByCorrelationId("ghost"));
    }

    @Test
    void existsByCorrelationId_trueWhenPresent() {
        store.save(pendingReply("corr-5", Instant.now().plusSeconds(60)));
        assertTrue(store.existsByCorrelationId("corr-5"));
    }

    @Test
    void existsByCorrelationId_falseWhenAbsent() {
        assertFalse(store.existsByCorrelationId("missing"));
    }

    @Test
    void findExpiredBefore_returnsOnlyExpired() {
        Instant now = Instant.now();
        store.save(pendingReply("expired-1", now.minusSeconds(1)));
        store.save(pendingReply("expired-2", now.minusSeconds(10)));
        store.save(pendingReply("active-1", now.plusSeconds(60)));
        List<PendingReply> expired = store.findExpiredBefore(now);
        assertEquals(2, expired.size());
        assertTrue(expired.stream().allMatch(pr -> pr.expiresAt.isBefore(now)));
    }

    @Test
    void findExpiredBefore_noneExpired_returnsEmpty() {
        store.save(pendingReply("active", Instant.now().plusSeconds(60)));
        assertTrue(store.findExpiredBefore(Instant.now()).isEmpty());
    }

    @Test
    void deleteExpiredBefore_removesExpiredLeavesActive() {
        Instant now = Instant.now();
        store.save(pendingReply("expired", now.minusSeconds(5)));
        store.save(pendingReply("active", now.plusSeconds(60)));
        store.deleteExpiredBefore(now);
        assertFalse(store.existsByCorrelationId("expired"));
        assertTrue(store.existsByCorrelationId("active"));
    }

    private PendingReply pendingReply(String correlationId, Instant expiresAt) {
        PendingReply pr = new PendingReply();
        pr.correlationId = correlationId;
        pr.channelId = UUID.randomUUID();
        pr.instanceId = UUID.randomUUID();
        pr.expiresAt = expiresAt;
        return pr;
    }
}

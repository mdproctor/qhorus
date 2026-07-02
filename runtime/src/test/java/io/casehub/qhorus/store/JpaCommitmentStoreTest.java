package io.casehub.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaCommitmentStoreTest {

    @Inject
    CommitmentStore store;

    @Test
    @TestTransaction
    void saveAndFindById_happyPath() {
        Commitment saved = store.save(cmd("jpa-1"));
        assertNotNull(saved.id());
        assertTrue(store.findById(saved.id()).isPresent());
    }

    @Test
    @TestTransaction
    void saveAndFindByCorrelationId_happyPath() {
        store.save(cmd("jpa-corr-1"));
        Optional<Commitment> found = store.findByCorrelationId("jpa-corr-1");
        assertTrue(found.isPresent());
        assertEquals("jpa-corr-1", found.get().correlationId());
    }

    @Test
    @TestTransaction
    void save_withExplicitId_preservesId() {
        UUID       id = UUID.randomUUID();
        Commitment c  = cmd("jpa-id-1").toBuilder().id(id).build();
        store.save(c);
        assertEquals(id, store.findById(id).get().id());
    }

    @Test
    @TestTransaction
    void stateUpdate_persists() {
        Commitment c = store.save(cmd("jpa-state-1"));
        store.save(c.toBuilder().state(CommitmentState.FULFILLED).resolvedAt(Instant.now()).build());
        assertEquals(CommitmentState.FULFILLED,
                store.findByCorrelationId("jpa-state-1").get().state());
    }

    @Test
    @TestTransaction
    void findOpenByObligor_excludesTerminal() {
        UUID ch = UUID.randomUUID();
        store.save(cmd("jpa-ob-open", ch));
        Commitment done = store.save(cmd("jpa-ob-done", ch));
        store.save(done.toBuilder().state(CommitmentState.FULFILLED).resolvedAt(Instant.now()).build());

        assertEquals(1, store.findOpenByObligor("obl", ch).size());
    }

    @Test
    @TestTransaction
    void findExpiredBefore_excludesTerminalAndFuture() {
        Instant now = Instant.now();

        Commitment expired = store.save(cmd("jpa-exp-1"));
        store.save(expired.toBuilder().expiresAt(now.minusSeconds(10)).build());

        Commitment future = store.save(cmd("jpa-exp-2"));
        store.save(future.toBuilder().expiresAt(now.plusSeconds(60)).build());

        Commitment terminalExpired = store.save(cmd("jpa-exp-3"));
        store.save(terminalExpired.toBuilder().expiresAt(now.minusSeconds(5))
                .state(CommitmentState.DECLINED).resolvedAt(now).build());

        List<Commitment> expiredResults = store.findExpiredBefore(now);
        assertEquals(1, expiredResults.size());
        assertEquals("jpa-exp-1", expiredResults.get(0).correlationId());
    }

    @Test
    @TestTransaction
    void deleteById_removesFromDb() {
        Commitment c = store.save(cmd("jpa-del-1"));
        store.deleteById(c.id());
        assertTrue(store.findById(c.id()).isEmpty());
    }

    @Test
    @TestTransaction
    void deleteAll_removesAllForChannelLeavesOtherChannels() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        store.save(cmd("jpa-dall-1", ch1));
        store.save(cmd("jpa-dall-2", ch1));
        store.save(cmd("jpa-dall-3", ch2));

        long deleted = store.deleteAll(ch1);

        assertEquals(2, deleted);
        assertTrue(store.findByCorrelationId("jpa-dall-1").isEmpty());
        assertTrue(store.findByCorrelationId("jpa-dall-2").isEmpty());
        assertTrue(store.findByCorrelationId("jpa-dall-3").isPresent());
    }

    @Test
    @TestTransaction
    void deleteExpiredBefore_bulkDelete() {
        Instant now = Instant.now();

        Commitment c1 = store.save(cmd("jpa-bulk-1"));
        store.save(c1.toBuilder().expiresAt(now.minusSeconds(5)).build());

        Commitment c2 = store.save(cmd("jpa-bulk-2"));
        store.save(c2.toBuilder().expiresAt(now.plusSeconds(60)).build());

        assertEquals(1, store.deleteExpiredBefore(now));
        assertTrue(store.findByCorrelationId("jpa-bulk-2").isPresent());
    }

    private Commitment cmd(String correlationId) {
        return cmd(correlationId, UUID.randomUUID());
    }

    private Commitment cmd(String correlationId, UUID channelId) {
        return Commitment.builder()
                .correlationId(correlationId)
                .channelId(channelId)
                .messageType(MessageType.COMMAND)
                .requester("req")
                .obligor("obl")
                .state(CommitmentState.OPEN)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                .build();
    }
}

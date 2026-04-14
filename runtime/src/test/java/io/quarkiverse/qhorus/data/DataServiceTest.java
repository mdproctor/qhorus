package io.quarkiverse.qhorus.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DataServiceTest {

    @Inject
    DataService dataService;

    @Test
    @TestTransaction
    void storeCreatesArtefact() {
        SharedData data = dataService.store("report-1", "Analysis report", "alice",
                "Full analysis content here", false, true);

        assertNotNull(data.id);
        assertEquals("report-1", data.key);
        assertEquals("Analysis report", data.description);
        assertEquals("alice", data.createdBy);
        assertEquals("Full analysis content here", data.content);
        assertTrue(data.complete);
        assertEquals("Full analysis content here".length(), data.sizeBytes);
        assertNotNull(data.createdAt);
        assertNotNull(data.updatedAt);
    }

    @Test
    @TestTransaction
    void getByKeyReturnsArtefact() {
        dataService.store("get-by-key-test", "desc", "alice", "content", false, true);

        var found = dataService.getByKey("get-by-key-test");

        assertTrue(found.isPresent());
        assertEquals("get-by-key-test", found.get().key);
    }

    @Test
    @TestTransaction
    void getByKeyReturnsEmptyWhenNotFound() {
        assertTrue(dataService.getByKey("no-such-key").isEmpty());
    }

    @Test
    @TestTransaction
    void getByUuidReturnsArtefact() {
        SharedData stored = dataService.store("get-by-uuid-test", "desc", "alice", "content", false, true);

        var found = dataService.getByUuid(stored.id);

        assertTrue(found.isPresent());
        assertEquals(stored.id, found.get().id);
    }

    @Test
    @TestTransaction
    void chunkedUploadAppendsContent() {
        dataService.store("chunked-key", "Big report", "alice", "chunk1", false, false);
        dataService.store("chunked-key", null, "alice", " chunk2", true, false);
        SharedData final_ = dataService.store("chunked-key", null, "alice", " chunk3", true, true);

        assertEquals("chunk1 chunk2 chunk3", final_.content);
        assertTrue(final_.complete);
        assertEquals("chunk1 chunk2 chunk3".length(), final_.sizeBytes);
    }

    @Test
    @TestTransaction
    void incompleteArtefactIsNotComplete() {
        SharedData data = dataService.store("partial", "desc", "alice", "chunk1", false, false);

        assertFalse(data.complete);
    }

    @Test
    @TestTransaction
    void listAllReturnsStoredArtefacts() {
        dataService.store("list-a", "desc", "alice", "content a", false, true);
        dataService.store("list-b", "desc", "bob", "content b", false, true);

        List<SharedData> all = dataService.listAll();

        assertTrue(all.stream().anyMatch(d -> "list-a".equals(d.key)));
        assertTrue(all.stream().anyMatch(d -> "list-b".equals(d.key)));
    }

    @Test
    @TestTransaction
    void claimAndReleaseLifecycle() {
        SharedData data = dataService.store("lifecycle-test", "desc", "alice", "content", false, true);
        UUID instanceId = UUID.randomUUID();

        dataService.claim(data.id, instanceId);
        assertFalse(dataService.isGcEligible(data.id),
                "artefact with active claim should not be GC eligible");

        dataService.release(data.id, instanceId);
        assertTrue(dataService.isGcEligible(data.id),
                "artefact with no claims should be GC eligible");
    }

    @Test
    @TestTransaction
    void incompleteArtefactIsNotGcEligibleEvenWithNoClaims() {
        SharedData data = dataService.store("incomplete-gc-test", "desc", "alice", "chunk1", false, false);

        // No claims, but not complete — should NOT be GC eligible
        assertFalse(dataService.isGcEligible(data.id),
                "incomplete artefact should never be GC eligible");
    }
}

package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;

class InMemoryDataStoreTest {

    private InMemoryDataStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDataStore();
    }

    private SharedData makeData(String key, String createdBy, boolean complete) {
        SharedData d = new SharedData();
        d.key = key;
        d.createdBy = createdBy;
        d.complete = complete;
        d.content = "some content";
        return d;
    }

    private ArtefactClaim makeClaim(UUID artefactId, UUID instanceId) {
        ArtefactClaim c = new ArtefactClaim();
        c.artefactId = artefactId;
        c.instanceId = instanceId;
        return c;
    }

    @Test
    void put_assignsUuid_whenIdIsNull() {
        SharedData d = makeData("key-1", "agent-a", true);
        SharedData saved = store.put(d);
        assertNotNull(saved.id);
    }

    @Test
    void put_preservesExistingId() {
        SharedData d = makeData("key-1", "agent-a", true);
        d.id = UUID.randomUUID();
        UUID expected = d.id;
        store.put(d);
        assertEquals(expected, store.find(expected).get().id);
    }

    @Test
    void find_returnsData_whenPresent() {
        SharedData d = makeData("key-1", "agent-a", false);
        store.put(d);
        assertTrue(store.find(d.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenNotFound() {
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByKey_returnsData() {
        store.put(makeData("my-key", "agent-a", true));
        assertTrue(store.findByKey("my-key").isPresent());
        assertEquals("my-key", store.findByKey("my-key").get().key);
    }

    @Test
    void findByKey_returnsEmpty_whenNoMatch() {
        assertTrue(store.findByKey("no-such-key").isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        store.put(makeData("k1", "agent-a", true));
        store.put(makeData("k2", "agent-b", false));
        assertEquals(2, store.scan(DataQuery.all()).size());
    }

    @Test
    void scan_completeOnly_returnsOnlyComplete() {
        store.put(makeData("k1", "agent-a", true));
        store.put(makeData("k2", "agent-b", false));

        List<SharedData> results = store.scan(DataQuery.completeOnly());
        assertEquals(1, results.size());
        assertTrue(results.get(0).complete);
    }

    @Test
    void scan_byCreator_returnsMatchingOnly() {
        store.put(makeData("k1", "agent-a", true));
        store.put(makeData("k2", "agent-b", true));

        List<SharedData> results = store.scan(DataQuery.byCreator("agent-a"));
        assertEquals(1, results.size());
        assertEquals("agent-a", results.get(0).createdBy);
    }

    @Test
    void putClaim_assignsUuid_whenIdIsNull() {
        UUID artefactId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        ArtefactClaim saved = store.putClaim(makeClaim(artefactId, instanceId));
        assertNotNull(saved.id);
    }

    @Test
    void countClaims_returnsCorrectCount() {
        UUID artefactId = UUID.randomUUID();
        store.putClaim(makeClaim(artefactId, UUID.randomUUID()));
        store.putClaim(makeClaim(artefactId, UUID.randomUUID()));
        store.putClaim(makeClaim(UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(2, store.countClaims(artefactId));
    }

    @Test
    void deleteClaim_removesMatchingClaim() {
        UUID artefactId = UUID.randomUUID();
        UUID instanceA = UUID.randomUUID();
        UUID instanceB = UUID.randomUUID();

        store.putClaim(makeClaim(artefactId, instanceA));
        store.putClaim(makeClaim(artefactId, instanceB));

        store.deleteClaim(artefactId, instanceA);
        assertEquals(1, store.countClaims(artefactId));
    }

    @Test
    void deleteClaim_doesNotRemoveOtherArtefactsClaims() {
        UUID artefactA = UUID.randomUUID();
        UUID artefactB = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        store.putClaim(makeClaim(artefactA, instanceId));
        store.putClaim(makeClaim(artefactB, instanceId));

        store.deleteClaim(artefactA, instanceId);

        assertEquals(0, store.countClaims(artefactA));
        assertEquals(1, store.countClaims(artefactB));
    }

    @Test
    void delete_removesDataAndItsArtefactClaims() {
        SharedData d = makeData("k1", "agent-a", true);
        store.put(d);
        store.putClaim(makeClaim(d.id, UUID.randomUUID()));
        store.putClaim(makeClaim(d.id, UUID.randomUUID()));

        store.delete(d.id);

        assertTrue(store.find(d.id).isEmpty());
        assertEquals(0, store.countClaims(d.id));
    }

    @Test
    void clear_removesAll() {
        SharedData d = makeData("k1", "agent-a", true);
        store.put(d);
        store.putClaim(makeClaim(d.id, UUID.randomUUID()));
        store.clear();

        assertTrue(store.scan(DataQuery.all()).isEmpty());
        assertEquals(0, store.countClaims(d.id));
    }
}

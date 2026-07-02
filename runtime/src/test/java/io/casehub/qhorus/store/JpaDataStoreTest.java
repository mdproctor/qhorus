package io.casehub.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.query.DataQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaDataStoreTest {

    @Inject
    DataStore dataStore;

    private SharedData buildData(String key, boolean complete) {
        String content = "content-" + UUID.randomUUID();
        return SharedData.builder(key)
                .content(content)
                .createdBy("agent-a")
                .complete(complete)
                .sizeBytes(content.length())
                .build();
    }

    private ArtefactClaim buildClaim(UUID artefactId, UUID instanceId) {
        return new ArtefactClaim(null, artefactId, instanceId, null);
    }

    @Test
    @TestTransaction
    void put_persistsDataAndAssignsId() {
        SharedData saved = dataStore.put(buildData("put-test-" + UUID.randomUUID(), true));

        assertNotNull(saved.id());
        assertEquals("agent-a", saved.createdBy());
    }

    @Test
    @TestTransaction
    void find_returnsData_whenExists() {
        SharedData d = dataStore.put(buildData("find-test-" + UUID.randomUUID(), true));

        Optional<SharedData> found = dataStore.find(d.id());

        assertTrue(found.isPresent());
        assertEquals(d.id(), found.get().id());
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(dataStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByKey_returnsData_whenExists() {
        String     key = "key-test-" + UUID.randomUUID();
        SharedData d   = dataStore.put(buildData(key, true));

        Optional<SharedData> found = dataStore.findByKey(key);

        assertTrue(found.isPresent());
        assertEquals(key, found.get().key());
    }

    @Test
    @TestTransaction
    void findByKey_returnsEmpty_whenNotFound() {
        assertTrue(dataStore.findByKey("no-such-key-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_completeOnly_returnsOnlyCompleteData() {
        String     suffix   = UUID.randomUUID().toString();
        SharedData complete = dataStore.put(buildData("complete-" + suffix, true));
        SharedData incomplete = dataStore.put(buildData("incomplete-" + suffix, false));

        List<SharedData> results = dataStore.scan(DataQuery.completeOnly());

        assertTrue(results.stream().anyMatch(d -> d.key().equals(complete.key())));
        assertTrue(results.stream().noneMatch(d -> d.key().equals(incomplete.key())));
    }

    @Test
    @TestTransaction
    void scan_byCreator_returnsMatchingOnly() {
        String     suffix = UUID.randomUUID().toString();
        SharedData mine   = dataStore.put(buildData("mine-" + suffix, true)
                .toBuilder().createdBy("agent-x-" + suffix).build());
        SharedData theirs = dataStore.put(buildData("theirs-" + suffix, true)
                .toBuilder().createdBy("agent-y-" + suffix).build());

        List<SharedData> results = dataStore.scan(DataQuery.byCreator("agent-x-" + suffix));

        assertTrue(results.stream().anyMatch(d -> d.key().equals(mine.key())));
        assertTrue(results.stream().noneMatch(d -> d.key().equals(theirs.key())));
    }

    @Test
    @TestTransaction
    void putClaim_andCountClaims_roundTrip() {
        SharedData d = dataStore.put(buildData("claim-test-" + UUID.randomUUID(), true));

        UUID instanceId1 = UUID.randomUUID();
        UUID instanceId2 = UUID.randomUUID();
        dataStore.putClaim(buildClaim(d.id(), instanceId1));
        dataStore.putClaim(buildClaim(d.id(), instanceId2));

        assertEquals(2, dataStore.countClaims(d.id()));
    }

    @Test
    @TestTransaction
    void deleteClaim_reducesCount() {
        SharedData d = dataStore.put(buildData("del-claim-test-" + UUID.randomUUID(), true));

        UUID instanceId = UUID.randomUUID();
        dataStore.putClaim(buildClaim(d.id(), instanceId));
        assertEquals(1, dataStore.countClaims(d.id()));

        dataStore.deleteClaim(d.id(), instanceId);
        assertEquals(0, dataStore.countClaims(d.id()));
    }

    @Test
    @TestTransaction
    void delete_removesDataAndClaims() {
        SharedData d = dataStore.put(buildData("delete-test-" + UUID.randomUUID(), true));
        dataStore.putClaim(buildClaim(d.id(), UUID.randomUUID()));

        dataStore.delete(d.id());

        assertTrue(dataStore.find(d.id()).isEmpty());
        assertEquals(0, dataStore.countClaims(d.id()));
    }
}

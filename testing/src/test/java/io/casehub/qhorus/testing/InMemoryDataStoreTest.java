package io.casehub.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.query.DataQuery;
import io.casehub.qhorus.testing.contract.DataStoreContractTest;

class InMemoryDataStoreTest extends DataStoreContractTest {
    private final InMemoryDataStore store = new InMemoryDataStore();

    @Override protected SharedData put(SharedData d) { return store.put(d); }
    @Override protected Optional<SharedData> find(UUID id) { return store.find(id); }
    @Override protected Optional<SharedData> findByKey(String key) { return store.findByKey(key); }
    @Override protected List<SharedData> scan(DataQuery q) { return store.scan(q); }
    @Override protected ArtefactClaim putClaim(ArtefactClaim c) { return store.putClaim(c); }
    @Override protected void deleteClaim(UUID aId, UUID iId) { store.deleteClaim(aId, iId); }
    @Override protected int countClaims(UUID aId) { return store.countClaims(aId); }
    @Override protected void reset() { store.clear(); }

    @Test
    void scan_completeOnly_returnsOnlyComplete() {
        store.put(data("k1-" + UUID.randomUUID()));
        SharedData incomplete = data("k2-" + UUID.randomUUID()).toBuilder().complete(false).build();
        store.put(incomplete);

        List<SharedData> results = store.scan(DataQuery.completeOnly());
        assertTrue(results.stream().allMatch(SharedData::complete));
        assertTrue(results.stream().noneMatch(d -> d.key().equals(incomplete.key())));
    }

    @Test
    void deleteClaim_doesNotRemoveOtherArtefactsClaims() {
        SharedData d1 = store.put(data("da-" + UUID.randomUUID()));
        SharedData d2 = store.put(data("db-" + UUID.randomUUID()));
        UUID instanceId = UUID.randomUUID();

        store.putClaim(new ArtefactClaim(null, d1.id(), instanceId, null));
        store.putClaim(new ArtefactClaim(null, d2.id(), instanceId, null));

        store.deleteClaim(d1.id(), instanceId);

        assertEquals(0, store.countClaims(d1.id()));
        assertEquals(1, store.countClaims(d2.id()));
    }

    @Test
    void delete_removesDataAndItsArtefactClaims() {
        SharedData d = store.put(data("del-" + UUID.randomUUID()));
        store.putClaim(new ArtefactClaim(null, d.id(), UUID.randomUUID(), null));

        store.delete(d.id());

        assertTrue(store.find(d.id()).isEmpty());
        assertEquals(0, store.countClaims(d.id()));
    }
}

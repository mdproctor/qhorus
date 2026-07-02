package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.data.ArtefactClaim;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.ReactiveDataStore;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaDataStoreTest {

    @Inject
    ReactiveDataStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        SharedData data = sharedData("rx-key-" + UUID.randomUUID());
        asserter.assertThat(
                () -> Panache.withTransaction("qhorus", () -> store.put(data)),
                saved -> assertNotNull(saved.id()));
    }

    @Test
    @RunOnVertxContext
    void findByKey_returnsData_whenExists(UniAsserter asserter) {
        String key = "rx-lookup-" + UUID.randomUUID();
        SharedData data = sharedData(key);
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(data)))
                .assertThat(
                        () -> store.findByKey(key),
                        opt -> assertTrue(opt.isPresent()));
    }

    @Test
    @RunOnVertxContext
    void putClaim_andCountClaims(UniAsserter asserter) {
        SharedData data       = sharedData("rx-claim-" + UUID.randomUUID());
        UUID       instanceId = UUID.randomUUID();
        final UUID[] savedId = new UUID[1];
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(data))
                        .invoke(s -> savedId[0] = s.id()))
                .execute(() -> Panache.withTransaction("qhorus",
                        () -> store.putClaim(new ArtefactClaim(null, savedId[0], instanceId, null))))
                .assertThat(
                        () -> store.countClaims(savedId[0]),
                        count -> assertEquals(1, count));
    }

    private SharedData sharedData(String key) {
        return SharedData.builder(key).createdBy("test-rx").build();
    }
}

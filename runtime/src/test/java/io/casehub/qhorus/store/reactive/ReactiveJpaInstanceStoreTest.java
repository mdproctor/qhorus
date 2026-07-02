package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.ReactiveInstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaInstanceStoreTest {

    @Inject
    ReactiveInstanceStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Instance inst = instance("rx-agent-" + UUID.randomUUID());
        asserter.assertThat(
                () -> Panache.withTransaction("qhorus", () -> store.put(inst)),
                saved -> assertNotNull(saved.id()));
    }

    @Test
    @RunOnVertxContext
    void putCapabilities_andFindCapabilities(UniAsserter asserter) {
        String instId = "cap-rx-" + UUID.randomUUID();
        Instance inst = instance(instId);
        final UUID[] savedId = new UUID[1];
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(inst))
                        .invoke(s -> savedId[0] = s.id()))
                .execute(() -> store.putCapabilities(savedId[0], List.of("search", "plan")))
                .assertThat(
                        () -> store.findCapabilities(savedId[0]),
                        caps -> {
                            assertTrue(caps.contains("search"));
                            assertTrue(caps.contains("plan"));
                        });
    }

    @Test
    @RunOnVertxContext
    void scan_byCapability_returnsMatchingInstances(UniAsserter asserter) {
        String aId = "rx-cap-a-" + UUID.randomUUID();
        String bId = "rx-cap-b-" + UUID.randomUUID();
        Instance a = instance(aId);
        Instance b = instance(bId);
        final UUID[] aUuid = new UUID[1];
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(a))
                        .invoke(s -> aUuid[0] = s.id()))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(b)))
                .execute(() -> store.putCapabilities(aUuid[0], List.of("review")))
                .assertThat(
                        () -> store.scan(InstanceQuery.byCapability("review")),
                        results -> {
                            assertEquals(1, results.size());
                            assertEquals(aId, results.get(0).instanceId());
                        });
    }

    private Instance instance(String instanceId) {
        return Instance.builder(instanceId).status("online").build();
    }
}

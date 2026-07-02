package io.casehub.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaInstanceStoreTest {

    @Inject
    InstanceStore instanceStore;

    private Instance buildInstance(String status) {
        return Instance.builder("agent-" + UUID.randomUUID())
                .status(status)
                .lastSeen(Instant.now())
                .build();
    }

    private Instance buildInstance(String status, String instanceId) {
        return Instance.builder(instanceId)
                .status(status)
                .lastSeen(Instant.now())
                .build();
    }

    @Test
    @TestTransaction
    void put_persistsInstanceAndAssignsId() {
        Instance saved = instanceStore.put(buildInstance("online"));

        assertNotNull(saved.id());
    }

    @Test
    @TestTransaction
    void find_returnsInstance_whenExists() {
        Instance inst = instanceStore.put(buildInstance("online"));

        Optional<Instance> found = instanceStore.find(inst.id());

        assertTrue(found.isPresent());
        assertEquals(inst.id(), found.get().id());
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(instanceStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByInstanceId_returnsInstance_whenExists() {
        Instance inst = instanceStore.put(buildInstance("online"));

        Optional<Instance> found = instanceStore.findByInstanceId(inst.instanceId());

        assertTrue(found.isPresent());
        assertEquals(inst.instanceId(), found.get().instanceId());
    }

    @Test
    @TestTransaction
    void findByInstanceId_returnsEmpty_whenNotFound() {
        assertTrue(instanceStore.findByInstanceId("no-such-agent-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_online_returnsOnlyOnlineInstances() {
        String suffix = UUID.randomUUID().toString();
        Instance online = instanceStore.put(buildInstance("online", "online-" + suffix));
        Instance stale = instanceStore.put(buildInstance("stale", "stale-" + suffix));

        List<Instance> results = instanceStore.scan(InstanceQuery.online());

        assertTrue(results.stream().anyMatch(i -> i.instanceId().equals(online.instanceId())));
        assertTrue(results.stream().noneMatch(i -> i.instanceId().equals(stale.instanceId())));
    }

    @Test
    @TestTransaction
    void scan_staleOlderThan_returnsOnlyOldInstances() {
        String suffix = UUID.randomUUID().toString();

        Instance recent = instanceStore.put(buildInstance("online", "recent-" + suffix));
        Instance old = instanceStore.put(buildInstance("online", "old-" + suffix)
                .toBuilder().lastSeen(Instant.now().minusSeconds(3600)).build());

        Instant        threshold = Instant.now().minusSeconds(1800);
        List<Instance> results   = instanceStore.scan(InstanceQuery.staleOlderThan(threshold));

        assertTrue(results.stream().anyMatch(i -> i.instanceId().equals(old.instanceId())));
        assertTrue(results.stream().noneMatch(i -> i.instanceId().equals(recent.instanceId())));
    }

    @Test
    @TestTransaction
    void putCapabilities_andFindCapabilities_roundTrip() {
        Instance inst = instanceStore.put(buildInstance("online"));

        instanceStore.putCapabilities(inst.id(), List.of("code-review", "summarise"));
        List<String> caps = instanceStore.findCapabilities(inst.id());

        assertEquals(2, caps.size());
        assertTrue(caps.contains("code-review"));
        assertTrue(caps.contains("summarise"));
    }

    @Test
    @TestTransaction
    void putCapabilities_replacesExisting() {
        Instance inst = instanceStore.put(buildInstance("online"));

        instanceStore.putCapabilities(inst.id(), List.of("old-cap"));
        instanceStore.putCapabilities(inst.id(), List.of("new-cap-a", "new-cap-b"));

        List<String> caps = instanceStore.findCapabilities(inst.id());

        assertEquals(2, caps.size());
        assertFalse(caps.contains("old-cap"));
        assertTrue(caps.contains("new-cap-a"));
        assertTrue(caps.contains("new-cap-b"));
    }

    @Test
    @TestTransaction
    void scan_byCapability_returnsMatchingInstances() {
        String suffix = UUID.randomUUID().toString();
        String tag = "unique-cap-" + suffix;

        Instance withCap = instanceStore.put(buildInstance("online", "with-cap-" + suffix));
        instanceStore.putCapabilities(withCap.id(), List.of(tag));

        Instance noCap = instanceStore.put(buildInstance("online", "no-cap-" + suffix));

        List<Instance> results = instanceStore.scan(InstanceQuery.byCapability(tag));

        assertTrue(results.stream().anyMatch(i -> i.id().equals(withCap.id())));
        assertTrue(results.stream().noneMatch(i -> i.id().equals(noCap.id())));
    }

    @Test
    @TestTransaction
    void delete_removesInstance() {
        Instance inst = instanceStore.put(buildInstance("online"));
        instanceStore.putCapabilities(inst.id(), List.of("some-cap"));

        instanceStore.delete(inst.id());

        assertTrue(instanceStore.find(inst.id()).isEmpty());
        assertTrue(instanceStore.findCapabilities(inst.id()).isEmpty());
    }
}

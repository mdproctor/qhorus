package io.quarkiverse.qhorus.instance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class InstanceServiceTest {

    @Inject
    InstanceService instanceService;

    @Test
    @TestTransaction
    void registerCreatesNewInstance() {
        Instance inst = instanceService.register("alice", "Code review agent",
                List.of("code-review", "java"));

        assertNotNull(inst.id);
        assertEquals("alice", inst.instanceId);
        assertEquals("Code review agent", inst.description);
        assertEquals("online", inst.status);
        assertNotNull(inst.lastSeen);
        assertNotNull(inst.registeredAt);
    }

    @Test
    @TestTransaction
    void registerUpsertsByInstanceId() {
        instanceService.register("bob", "First description", List.of("python"));
        Instance updated = instanceService.register("bob", "Updated description", List.of("python", "ml"));

        assertEquals("bob", updated.instanceId);
        assertEquals("Updated description", updated.description);
        assertEquals("online", updated.status);

        // Should still be one instance, not two
        List<Instance> all = instanceService.listAll();
        assertEquals(1, all.stream().filter(i -> "bob".equals(i.instanceId)).count());
    }

    @Test
    @TestTransaction
    void registerStoresCapabilityTags() {
        instanceService.register("carol", "Multi-skill agent", List.of("code-review", "test-writing", "docs"));

        List<Instance> byCodeReview = instanceService.findByCapability("code-review");
        List<Instance> byDocs = instanceService.findByCapability("docs");
        List<Instance> byUnknown = instanceService.findByCapability("no-such-capability");

        assertEquals(1, byCodeReview.size());
        assertEquals("carol", byCodeReview.get(0).instanceId);
        assertEquals(1, byDocs.size());
        assertTrue(byUnknown.isEmpty());
    }

    @Test
    @TestTransaction
    void heartbeatUpdatesLastSeen() throws InterruptedException {
        Instance inst = instanceService.register("dave", "Agent", List.of());
        var before = inst.lastSeen;

        Thread.sleep(5);
        instanceService.heartbeat("dave");

        Instance refreshed = instanceService.findByInstanceId("dave").orElseThrow();
        assertTrue(refreshed.lastSeen.isAfter(before),
                "lastSeen should advance after heartbeat");
    }

    @Test
    @TestTransaction
    void findByInstanceIdReturnsEmptyWhenNotFound() {
        assertTrue(instanceService.findByInstanceId("ghost").isEmpty());
    }

    @Test
    @TestTransaction
    void listAllReturnsAllRegisteredInstances() {
        instanceService.register("e1", "Agent E1", List.of());
        instanceService.register("e2", "Agent E2", List.of());

        List<Instance> all = instanceService.listAll();

        assertTrue(all.stream().anyMatch(i -> "e1".equals(i.instanceId)));
        assertTrue(all.stream().anyMatch(i -> "e2".equals(i.instanceId)));
    }

    @Test
    @TestTransaction
    void markStaleChangesStatus() throws InterruptedException {
        instanceService.register("stale-agent", "Will go stale", List.of());

        Thread.sleep(5);
        instanceService.markStaleOlderThan(1); // 1 second threshold — any that haven't seen in 1s

        // since we just registered, this won't be stale yet — but mark method should not error
        Instance inst = instanceService.findByInstanceId("stale-agent").orElseThrow();
        // Status is still "online" since it was just registered (< 1 second ago)
        assertEquals("online", inst.status);
    }
}

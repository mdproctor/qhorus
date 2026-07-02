package io.casehub.qhorus.runtime.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ChannelStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies registerBackend() dedup guard: same backendId registers only once. Refs #204.
 */
@QuarkusTest
class ChannelGatewayDedupTest {

    @Inject
    ChannelGateway gateway;

    @Inject
    ChannelStore channelStore;

    @Test
    @TestTransaction
    void registerBackend_dedup_sameBackendIdRegistersOnce() {
        Channel ch = channelStore.put(Channel.builder("dedup-test-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND).build());

        ChannelRef ref = new ChannelRef(ch.id(), ch.name());
        gateway.initChannel(ch.id(), ref);

        // Stub backend — inline anonymous class avoids module cycle with testing/
        String testBackendId = "test-backend-" + UUID.randomUUID();
        ChannelBackend stub = new ChannelBackend() {
            @Override public String backendId() { return testBackendId; }
            @Override public ActorType actorType() { return ActorType.AGENT; }
            @Override public void open(ChannelRef r, Map<String, String> m) {}
            @Override public void post(ChannelRef r, OutboundMessage msg) {}
            @Override public void close(ChannelRef r) {}
        };

        gateway.registerBackend(ch.id(), stub, "agent");
        gateway.registerBackend(ch.id(), stub, "agent");  // second call — must be no-op

        List<ChannelGateway.BackendRegistration> backends = gateway.listBackends(ch.id());
        long dedupCount = backends.stream()
                .filter(b -> testBackendId.equals(b.backendId()))
                .count();
        assertEquals(1, dedupCount,
                "Same backendId registered twice must appear exactly once in listBackends");
    }

    @Test
    @TestTransaction
    void registerBackend_dedup_differentBackendIdsAreBothAdded() {
        Channel ch = channelStore.put(Channel.builder("two-backends-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND).build());

        ChannelRef ref = new ChannelRef(ch.id(), ch.name());
        gateway.initChannel(ch.id(), ref);

        ChannelBackend stubA = makeStub("backend-a-" + UUID.randomUUID());
        ChannelBackend stubB = makeStub("backend-b-" + UUID.randomUUID());

        gateway.registerBackend(ch.id(), stubA, "agent");
        gateway.registerBackend(ch.id(), stubB, "agent");

        List<ChannelGateway.BackendRegistration> backends = gateway.listBackends(ch.id());
        // qhorus-internal + backend-a + backend-b = 3 entries
        assertEquals(3, backends.size(),
                "Different backendIds should both be registered");
    }

    private static ChannelBackend makeStub(String id) {
        return new ChannelBackend() {
            @Override public String backendId() { return id; }
            @Override public ActorType actorType() { return ActorType.AGENT; }
            @Override public void open(ChannelRef r, Map<String, String> m) {}
            @Override public void post(ChannelRef r, OutboundMessage msg) {}
            @Override public void close(ChannelRef r) {}
        };
    }
}

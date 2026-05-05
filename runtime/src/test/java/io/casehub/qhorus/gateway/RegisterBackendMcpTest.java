package io.casehub.qhorus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DuplicateParticipatingBackendException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.BackendInfo;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.RegisterBackendResult;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class RegisterBackendMcpTest {

    @Inject QhorusMcpTools tools;
    @Inject ChannelGateway gateway;

    @BeforeEach
    @Transactional
    void setUp() {
        tools.createChannel("reg-back-1", "test", "append", null, null, null, null, null, null);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        try { tools.deleteChannel("reg-back-1", null, null); } catch (Exception ignored) {}
    }

    @Test
    void registerBackend_unknownBackendId_returnsError() {
        // "no-such-backend" is not a registered CDI bean
        assertThrows(ToolCallException.class,
                () -> tools.registerBackend("reg-back-1", "no-such-backend", "human_observer"));
    }

    @Test
    void registerBackend_qhorusInternal_asObserver_returnsError() {
        // Cannot register qhorus-internal as a participating/observer backend
        // (it's already the agent backend — different role entirely)
        assertThrows(ToolCallException.class,
                () -> tools.registerBackend("reg-back-1", "qhorus-internal", "human_observer"));
    }

    @Test
    void registerBackend_invalidBackendType_returnsError() {
        assertThrows(ToolCallException.class,
                () -> tools.registerBackend("reg-back-1", "no-such-backend", "invalid_type"));
    }

    @Test
    void registerBackend_unknownChannel_returnsError() {
        assertThrows(ToolCallException.class,
                () -> tools.registerBackend("no-such-channel", "no-such-backend", "human_observer"));
    }
}

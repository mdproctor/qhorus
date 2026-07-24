package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests create_channel and update_channel_binding MCP tools for connector binding support.
 * Refs #217
 */
@QuarkusTest
class ConnectorBindingToolTest {

    @Inject
    QhorusMcpTools tools;

    // ── create_channel with binding ────────────────────────────────────────────

    @Test
    @TestTransaction
    void createChannel_withBinding_populatesConnectorBindingInDetail() {
        String name = "ch-with-binding-" + UUID.randomUUID();

        ChannelDetail detail = tools.createChannel(name, "desc", null, null, null, null, null, null, null, null, null, null, null, null, "twilio", "+44123456789", "twilio-out", "+44123456789", null);

        assertNotNull(detail.connectorBinding(),
                "connectorBinding must be non-null when binding params are provided");
        assertEquals("twilio", detail.connectorBinding().inboundConnectorId());
        assertEquals("+44123456789", detail.connectorBinding().externalKey());
        assertEquals("twilio-out", detail.connectorBinding().outboundConnectorId());
        assertEquals("+44123456789", detail.connectorBinding().outboundDestination());
    }

    @Test
    @TestTransaction
    void createChannel_withoutBinding_hasNullConnectorBinding() {
        String name = "ch-no-binding-" + UUID.randomUUID();

        ChannelDetail detail = tools.createChannel(name, "desc", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertNull(detail.connectorBinding());
    }

    @Test
    @TestTransaction
    void createChannel_withPartialBinding_throwsToolCallException() {
        String name = "ch-partial-binding-" + UUID.randomUUID();

        ToolCallException ex = assertThrows(ToolCallException.class, () ->
                tools.createChannel(name, "desc", null, null, null, null, null, null, null, null, null, null, null, null, "twilio", "+44123456789", null, null, null));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    // ── update_channel_binding ─────────────────────────────────────────────────

    @Test
    @TestTransaction
    void updateChannelBinding_updatesOutboundFieldsAndReturnsDetail() {
        String name = "ch-upd-binding-" + UUID.randomUUID();
        tools.createChannel(name, "desc", null, null, null, null, null, null, null, null, null, null, null, null, "twilio", "+44111222333", "twilio-out", "+44111222333", null);

        ChannelDetail updated = tools.updateChannelBinding(name, "vonage-out", "+447999888777");

        assertNotNull(updated.connectorBinding());
        assertEquals("vonage-out", updated.connectorBinding().outboundConnectorId());
        assertEquals("+447999888777", updated.connectorBinding().outboundDestination());
        assertEquals("twilio", updated.connectorBinding().inboundConnectorId());
    }

    @Test
    @TestTransaction
    void updateChannelBinding_onChannelWithNoBinding_throwsToolCallException() {
        String name = "ch-nobind-upd-" + UUID.randomUUID();
        tools.createChannel(name, "desc", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        ToolCallException ex = assertThrows(ToolCallException.class, () ->
                tools.updateChannelBinding(name, "vonage-out", "+447999888777"));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    @TestTransaction
    void updateChannelBinding_onNonExistentChannel_throwsToolCallException() {
        ToolCallException ex = assertThrows(ToolCallException.class, () ->
                tools.updateChannelBinding("does-not-exist-" + UUID.randomUUID(),
                        "vonage-out", "+447999888777"));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }
}

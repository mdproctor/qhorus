package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DeniedTypesMcpTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void createChannel_withDeniedTypes_storesDeniedTypesInDetail() {
        ChannelDetail detail = tools.createChannel("oversight-mcp", "Oversight channel", null, null, null, null, null, null, null, "EVENT", null, null, null, null, null, null, null, null, null);
        assertThat(detail.deniedTypes()).isEqualTo("EVENT");
        assertThat(detail.allowedTypes()).isNull();
    }

    @Test
    @TestTransaction
    void sendMessage_deniedType_returnsAdvisory() {
        tools.createChannel("oversight-denied", "Oversight channel", null, null, null, null, null, null, null, "EVENT", null, null, null, null, null, null, null, null, null);

        // EVENT is not obligation-creating — dispatch succeeds with advisory
        io.casehub.qhorus.api.message.DispatchResult result = tools.sendMessage("oversight-denied", "telemetry-agent", "event",
                null, null, null, null, null, null, null, null, null);

        assertThat(result.advisories()).isNotEmpty();
        String adv = result.advisories().get(0);
        assertThat(adv).contains("denies");
        assertThat(adv).contains("EVENT");
    }

    @Test
    @TestTransaction
    void sendMessage_nonDeniedType_passesOnDeniedChannel() {
        tools.createChannel("oversight-pass", "Oversight channel", null, null, null, null, null, null, null, "EVENT", null, null, null, null, null, null, null, null, null);

        // COMMAND is not denied — should pass
        tools.sendMessage("oversight-pass", "overseer", "command",
                "proceed", null, null, null, null, null, null, null, null);
    }

    @Test
    @TestTransaction
    void createChannel_withInvalidDeniedType_throwsToolCallException() {
        assertThatThrownBy(() ->
                tools.createChannel("bad-denied", "Bad channel", null, null, null, null, null, null, null, "INVALID_TYPE", null, null, null, null, null, null, null, null, null))
                .isInstanceOf(ToolCallException.class);
    }

    @Test
    @TestTransaction
    void createChannel_withOverlappingTypes_throwsToolCallException() {
        assertThatThrownBy(() ->
                tools.createChannel(
                        "overlap-mcp", "Bad channel",
                        null, null, null, null, null, null,
                        "QUERY,COMMAND", "QUERY", null, null, null, null,
                        null, null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("QUERY");
    }
}

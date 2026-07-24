package io.casehub.qhorus.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@code set_channel_type_constraints} MCP tool.
 * Refs qhorus#244.
 */
@QuarkusTest
class SetChannelTypeConstraintsTest {

    @Inject QhorusMcpTools tools;

    @Test
    @TestTransaction
    void setDeniedTypes_updatesChannel() {
        String name = "oversight-" + System.nanoTime();
        tools.createChannel(name, "governance", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        ChannelDetail updated = tools.setChannelTypeConstraints(name, null, "EVENT");

        assertThat(updated.deniedTypes()).isEqualTo("EVENT");
        assertThat(updated.allowedTypes()).isNull();
    }

    @Test
    @TestTransaction
    void setAllowedTypes_updatesChannel() {
        String name = "observe-" + System.nanoTime();
        tools.createChannel(name, "telemetry", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        ChannelDetail updated = tools.setChannelTypeConstraints(name, "EVENT", null);

        assertThat(updated.allowedTypes()).isEqualTo("EVENT");
        assertThat(updated.deniedTypes()).isNull();
    }

    @Test
    @TestTransaction
    void nullForBoth_clearsConstraints() {
        String name = "constrained-" + System.nanoTime();
        tools.createChannel(name, "was constrained", "APPEND", null, null, null, null, null, "EVENT", "QUERY", null, null, null, null, null, null, null, null, null);

        ChannelDetail updated = tools.setChannelTypeConstraints(name, null, null);

        assertThat(updated.allowedTypes()).isNull();
        assertThat(updated.deniedTypes()).isNull();
    }

    @Test
    @TestTransaction
    void overlappingTypes_throws() {
        String name = "channel-" + System.nanoTime();
        tools.createChannel(name, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> tools.setChannelTypeConstraints(name, "EVENT,QUERY", "EVENT"))
                .isInstanceOf(ToolCallException.class)
                .getCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    @TestTransaction
    void unknownTypeName_throws() {
        String name = "channel-" + System.nanoTime();
        tools.createChannel(name, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> tools.setChannelTypeConstraints(name, "BOGUS_TYPE", null))
                .isInstanceOf(ToolCallException.class)
                .getCause()
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @TestTransaction
    void isFullReplacement_nullClearsExistingConstraint() {
        String name = "replace-test-" + System.nanoTime();
        tools.createChannel(name, "test", "APPEND", null, null, null, null, null, null, "QUERY", null, null, null, null, null, null, null, null, null);

        // Pass denied_types=EVENT but omit allowed_types → should clear allowed_types
        ChannelDetail updated = tools.setChannelTypeConstraints(name, null, "EVENT");

        assertThat(updated.allowedTypes()).isNull();
        assertThat(updated.deniedTypes()).isEqualTo("EVENT");
    }

    @Test
    @TestTransaction
    void unknownChannel_throws() {
        assertThatThrownBy(() -> tools.setChannelTypeConstraints("no-such-channel-" + System.nanoTime(), "EVENT", null))
                .isInstanceOf(ToolCallException.class)
                .getCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @TestTransaction
    void setConstraints_channelDetailReflectsUpdate() {
        String name = "detail-" + System.nanoTime();
        tools.createChannel(name, "test", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        // Pass unsorted input "RESPONSE,COMMAND" — asserts canonical sorted output "COMMAND,RESPONSE"
        ChannelDetail updated = tools.setChannelTypeConstraints(name, "RESPONSE,COMMAND", "EVENT");

        assertThat(updated.channelId()).isNotNull();
        assertThat(updated.name()).isEqualTo(name);
        assertThat(updated.allowedTypes()).isEqualTo("COMMAND,RESPONSE"); // sorted canonical form
        assertThat(updated.deniedTypes()).isEqualTo("EVENT");
    }
}

package io.casehub.qhorus.ledger;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.runtime.mcp.ReactiveQhorusMcpTools;
import io.casehub.qhorus.service.ReactiveTestProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Reactive timeline integration tests — verifies EVENT telemetry fields populated via
 * batch {@code findByMessageIds()} in {@link ReactiveQhorusMcpTools#blockingGetChannelTimeline}.
 *
 * <p>Requires PostgreSQL DevServices (Podman ≥ 4 GB). Activate when DevServices tests
 * are re-enabled for CI. Refs #262.
 */
@Disabled("Requires PostgreSQL DevServices — reactive Panache.withTransaction() cannot run on H2")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveChannelTimelineTest {

    @Inject
    ReactiveQhorusMcpTools tools;

    @Inject
    ReactiveMessageService reactiveMessageService;

    @Inject
    ChannelService channelService;

    private UUID createChannel(final String name) {
        final UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            final Channel ch = channelService.create(ChannelCreateRequest.builder(name)
                                                                         .description("reactive timeline test").build());
            id[0] = ch.id();
        });
        return id[0];
    }

    private void dispatchEvent(final UUID channelId, final String sender, final String toolName) {
        reactiveMessageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(MessageType.EVENT)
                .telemetry(String.format("{\"tool_name\":\"%s\",\"duration_ms\":10}", toolName))
                .actorType(ActorType.AGENT)
                .build()).await().indefinitely();
    }

    @Test
    void timeline_multiple_events_all_have_telemetry_populated() {
        final String channelName = "rt-timeline-" + UUID.randomUUID();
        final UUID channelId = createChannel(channelName);

        // All three events go to the SAME channel so getChannelTimeline(channelName) finds them.
        dispatchEvent(channelId, "agent-1", "tool-alpha");
        dispatchEvent(channelId, "agent-1", "tool-beta");
        dispatchEvent(channelId, "agent-1", "tool-gamma");

        final List<Map<String, Object>> timeline =
                tools.getChannelTimeline(channelName, null, 50).await().indefinitely();

        assertThat(timeline).hasSize(3);
        assertThat(timeline.stream().map(e -> e.get("tool_name")).toList())
                .containsExactlyInAnyOrder("tool-alpha", "tool-beta", "tool-gamma");
        assertThat(timeline.stream().map(e -> e.get("duration_ms")).toList())
                .allMatch(v -> v != null);
    }
}

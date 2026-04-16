package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the {@code list_events} MCP tool.
 *
 * <p>
 * Verifies that the event audit trail is queryable by channel with optional
 * filters for agent, time range, and cursor-based pagination.
 *
 * <p>
 * RED-phase: will not compile until {@code list_events} is added to
 * {@link QhorusMcpTools}.
 *
 * <p>
 * Refs #53, Epic #50.
 */
@QuarkusTest
@TestTransaction
class ListEventsTest {

    @Inject
    QhorusMcpTools tools;

    private static final String EVENT_PAYLOAD_TEMPLATE = "{\"tool_name\":\"%s\",\"duration_ms\":%d}";

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "LAST_WRITE", null, null, null, null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null, null, null);
        }
    }

    private void sendEvent(final String channel, final String sender, final String tool,
            final long durationMs) {
        tools.sendMessage(channel, sender, "event",
                String.format(EVENT_PAYLOAD_TEMPLATE, tool, durationMs),
                null, null, null, null);
    }

    // =========================================================================
    // Happy path — basic retrieval
    // =========================================================================

    @Test
    void listEvents_afterThreeEvents_returnsAllThree() {
        setup("le-basic-1", "agent-1");

        sendEvent("le-basic-1", "agent-1", "read_file", 10);
        sendEvent("le-basic-1", "agent-1", "write_file", 20);
        sendEvent("le-basic-1", "agent-1", "analyze", 300);

        final List<Map<String, Object>> events = tools.listEvents("le-basic-1", null, 20, null, null);

        assertEquals(3, events.size());
    }

    @Test
    void listEvents_returnsCorrectFields() {
        setup("le-fields-1", "agent-1");

        sendEvent("le-fields-1", "agent-1", "read_file", 42);

        final List<Map<String, Object>> events = tools.listEvents("le-fields-1", null, 20, null, null);

        assertEquals(1, events.size());
        final Map<String, Object> e = events.get(0);
        assertEquals("read_file", e.get("tool_name"));
        assertEquals(42L, e.get("duration_ms"));
        assertEquals("agent-1", e.get("agent_id"));
        assertNotNull(e.get("occurred_at"));
        assertNotNull(e.get("message_id"));
    }

    @Test
    void listEvents_returnsInChronologicalOrder() {
        setup("le-order-1", "agent-1");

        sendEvent("le-order-1", "agent-1", "first", 1);
        sendEvent("le-order-1", "agent-1", "second", 2);
        sendEvent("le-order-1", "agent-1", "third", 3);

        final List<Map<String, Object>> events = tools.listEvents("le-order-1", null, 20, null, null);

        assertEquals("first", events.get(0).get("tool_name"));
        assertEquals("second", events.get(1).get("tool_name"));
        assertEquals("third", events.get(2).get("tool_name"));
    }

    @Test
    void listEvents_unknownChannel_throwsOrReturnsError() {
        assertThrows(ToolCallException.class,
                () -> tools.listEvents("no-such-channel", null, 20, null, null));
    }

    // =========================================================================
    // agent_id filter
    // =========================================================================

    @Test
    void listEvents_agentIdFilter_returnsOnlyMatchingAgent() {
        setup("le-filter-agent-1", "agent-1", "agent-2");

        sendEvent("le-filter-agent-1", "agent-1", "tool-a", 10);
        sendEvent("le-filter-agent-1", "agent-2", "tool-b", 20);
        sendEvent("le-filter-agent-1", "agent-1", "tool-c", 30);

        final List<Map<String, Object>> events = tools.listEvents("le-filter-agent-1", null, 20, "agent-1", null);

        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> "agent-1".equals(e.get("agent_id"))));
    }

    @Test
    void listEvents_agentIdFilter_noMatch_returnsEmpty() {
        setup("le-filter-agent-2", "agent-1");

        sendEvent("le-filter-agent-2", "agent-1", "tool-a", 10);

        final List<Map<String, Object>> events = tools.listEvents("le-filter-agent-2", null, 20, "agent-x", null);

        assertTrue(events.isEmpty());
    }

    // =========================================================================
    // since filter
    // =========================================================================

    @Test
    void listEvents_sinceFilter_excludesOlderEntries() {
        setup("le-filter-since-1", "agent-1");

        sendEvent("le-filter-since-1", "agent-1", "before", 5);

        // Capture the timestamp between the two events
        final String sinceTimestamp = java.time.Instant.now().toString();

        sendEvent("le-filter-since-1", "agent-1", "after", 5);

        final List<Map<String, Object>> events = tools.listEvents("le-filter-since-1", null, 20, null, sinceTimestamp);

        assertEquals(1, events.size());
        assertEquals("after", events.get(0).get("tool_name"));
    }

    // =========================================================================
    // Cursor pagination
    // =========================================================================

    @Test
    void listEvents_limit_restrictsResults() {
        setup("le-limit-1", "agent-1");

        for (int i = 0; i < 5; i++) {
            sendEvent("le-limit-1", "agent-1", "tool-" + i, i * 10L);
        }

        final List<Map<String, Object>> page1 = tools.listEvents("le-limit-1", null, 3, null, null);

        assertEquals(3, page1.size());
    }

    @Test
    void listEvents_afterId_returnsNextPage() {
        setup("le-cursor-1", "agent-1");

        sendEvent("le-cursor-1", "agent-1", "tool-1", 10);
        sendEvent("le-cursor-1", "agent-1", "tool-2", 20);
        sendEvent("le-cursor-1", "agent-1", "tool-3", 30);

        final List<Map<String, Object>> page1 = tools.listEvents("le-cursor-1", null, 2, null, null);
        assertEquals(2, page1.size());

        // Use the sequence number of the last event on page 1 as cursor
        final Long cursor = (Long) page1.get(1).get("sequence_number");
        final List<Map<String, Object>> page2 = tools.listEvents("le-cursor-1", cursor, 2, null, null);

        assertEquals(1, page2.size());
        assertEquals("tool-3", page2.get(0).get("tool_name"));
    }

    // =========================================================================
    // Non-EVENT messages are not listed
    // =========================================================================

    @Test
    void listEvents_nonEventMessages_notIncluded() {
        setup("le-type-1", "agent-1");

        tools.sendMessage("le-type-1", "agent-1", "request", "Do something", null, null, null, null);
        tools.sendMessage("le-type-1", "agent-1", "status", "Working", null, null, null, null);
        sendEvent("le-type-1", "agent-1", "tool-x", 5);

        final List<Map<String, Object>> events = tools.listEvents("le-type-1", null, 20, null, null);

        assertEquals(1, events.size());
        assertEquals("tool-x", events.get(0).get("tool_name"));
    }
}

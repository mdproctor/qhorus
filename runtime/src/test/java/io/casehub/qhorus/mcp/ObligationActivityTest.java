package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for get_obligation_activity — cross-channel ledger correlation.
 *
 * Agents link EVENT messages to an obligation by passing the obligation's correlationId
 * when calling send_message on the observe channel. The correlationId field is the
 * canonical linkage mechanism; get_obligation_activity queries across all channels.
 *
 * Refs #134.
 */
@QuarkusTest
class ObligationActivityTest {

    @Inject
    QhorusMcpTools tools;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_entriesAcrossThreeChannels_returnedWithChannelName() {
        String caseId = "oa-hp-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work",      "Work",      "APPEND", null, null, null, null, null, null);
        tools.createChannel(caseId + "/observe",   "Observe",   "APPEND", null, null, null, null, null, "EVENT");
        tools.createChannel(caseId + "/oversight", "Oversight", "APPEND", null, null, null, null, null, "QUERY,COMMAND");

        // work: COMMAND starts the obligation
        tools.sendMessage(caseId + "/work", "coordinator", "COMMAND",
                "Analyse the codebase", corrId, null, null, null, null);

        // observe: EVENT with explicit correlationId — links this tool call to the obligation
        tools.sendMessage(caseId + "/observe", "oa-researcher", "EVENT",
                "{\"tool\":\"read_file\",\"path\":\"AuthService.java\"}",
                corrId, null, null, null, null);

        // work: STATUS extends the obligation
        tools.sendMessage(caseId + "/work", "oa-researcher", "STATUS",
                "Reading files — 40% done", corrId, null, null, null, null);

        // oversight: QUERY to human with same correlationId
        tools.sendMessage(caseId + "/oversight", "oa-researcher", "QUERY",
                "Is finding #2 in scope?", corrId, null, null, null, null);

        // work: DONE closes the obligation
        tools.sendMessage(caseId + "/work", "oa-researcher", "DONE",
                "Analysis complete.", corrId, null, null, null, null);

        List<Map<String, Object>> activity = tools.getObligationActivity(corrId, null, null);

        assertEquals(5, activity.size());

        // All entries have a non-null channel field
        for (Map<String, Object> entry : activity) {
            assertTrue(entry.containsKey("channel"), "Missing 'channel' field");
            assertNotNull(entry.get("channel"), "channel is null");
        }

        // Channel names in chronological order
        List<String> channels = activity.stream().map(e -> (String) e.get("channel")).toList();
        assertEquals(List.of(
                caseId + "/work",      // COMMAND
                caseId + "/observe",   // EVENT
                caseId + "/work",      // STATUS
                caseId + "/oversight", // QUERY
                caseId + "/work"),     // DONE
                channels);

        // Message types in order
        List<String> types = activity.stream().map(e -> (String) e.get("message_type")).toList();
        assertEquals(List.of("COMMAND", "EVENT", "STATUS", "QUERY", "DONE"), types);

        // All entries carry the correlationId
        for (Map<String, Object> entry : activity) {
            assertEquals(corrId, entry.get("correlation_id"),
                    "Entry missing correlationId: " + entry.get("message_type"));
        }
    }

    @Test
    void happyPath_entriesOrderedChronologically() {
        String caseId = "oa-order-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work",    "Work",    "APPEND", null, null, null, null, null, null);
        tools.createChannel(caseId + "/observe", "Observe", "APPEND", null, null, null, null, null, "EVENT");

        tools.sendMessage(caseId + "/work",    "agent-a", "COMMAND", "do it", corrId, null, null, null, null);
        // EVENT with explicit correlationId — agent links tool call to the obligation
        tools.sendMessage(caseId + "/observe", "agent-a", "EVENT",
                "{\"tool\":\"read_file\"}", corrId, null, null, null, null);
        tools.sendMessage(caseId + "/work",    "agent-a", "DONE",    "done",  corrId, null, null, null, null);

        List<Map<String, Object>> activity = tools.getObligationActivity(corrId, null, null);

        assertEquals(3, activity.size());

        // message_id is a global auto-increment — must be strictly ascending across channels
        List<Long> msgIds = activity.stream()
                .map(e -> (Long) e.get("message_id"))
                .toList();
        for (int i = 1; i < msgIds.size(); i++) {
            assertTrue(msgIds.get(i) > msgIds.get(i - 1),
                    "Entry " + i + ": message_id " + msgIds.get(i) + " must be > " + msgIds.get(i - 1));
        }
    }

    // ── Correctness ───────────────────────────────────────────────────────────

    @Test
    void correctness_eventWithExplicitCorrelationId_included() {
        String caseId = "oa-event-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work",    "Work",    "APPEND", null, null, null, null, null, null);
        tools.createChannel(caseId + "/observe", "Observe", "APPEND", null, null, null, null, null, "EVENT");

        tools.sendMessage(caseId + "/work",    "agent-a", "COMMAND", "analyse", corrId, null, null, null, null);
        // Agent correctly links EVENT to the obligation via explicit correlationId
        tools.sendMessage(caseId + "/observe", "agent-a", "EVENT",
                "{\"tool\":\"analyse_code\",\"duration_ms\":340}", corrId, null, null, null, null);
        tools.sendMessage(caseId + "/work",    "agent-a", "DONE",    "done",   corrId, null, null, null, null);

        List<Map<String, Object>> activity = tools.getObligationActivity(corrId, null, null);
        assertEquals(3, activity.size());
        assertEquals("EVENT", activity.get(1).get("message_type"));
        assertEquals(caseId + "/observe", activity.get(1).get("channel"));
    }

    @Test
    void correctness_eventWithoutCorrelationId_notIncluded() {
        String caseId = "oa-no-corr-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work",    "Work",    "APPEND", null, null, null, null, null, null);
        tools.createChannel(caseId + "/observe", "Observe", "APPEND", null, null, null, null, null, "EVENT");

        tools.sendMessage(caseId + "/work",    "agent-a", "COMMAND", "analyse", corrId, null, null, null, null);
        // EVENT without correlationId — agent did not link it to the obligation
        tools.sendMessage(caseId + "/observe", "agent-a", "EVENT",
                "{\"tool\":\"unrelated_call\"}", null, null, null, null, null);
        tools.sendMessage(caseId + "/work",    "agent-a", "DONE",    "done",   corrId, null, null, null, null);

        List<Map<String, Object>> activity = tools.getObligationActivity(corrId, null, null);
        // Only COMMAND and DONE — the unlinking EVENT is not returned
        assertEquals(2, activity.size());
        assertEquals(List.of("COMMAND", "DONE"),
                activity.stream().map(e -> (String) e.get("message_type")).toList());
    }

    @Test
    void correctness_messagesWithDifferentCorrelationId_notIncluded() {
        String caseId = "oa-isolation-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();
        String otherCorrId = "corr-other-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work", "Work", "APPEND", null, null, null, null, null, null);

        tools.sendMessage(caseId + "/work", "agent-a", "COMMAND", "target", corrId,      null, null, null, null);
        tools.sendMessage(caseId + "/work", "agent-b", "COMMAND", "noise",  otherCorrId, null, null, null, null);
        tools.sendMessage(caseId + "/work", "agent-a", "DONE",    "done",   corrId,      null, null, null, null);

        List<Map<String, Object>> activity = tools.getObligationActivity(corrId, null, null);

        assertEquals(2, activity.size());
        for (Map<String, Object> entry : activity) {
            assertEquals(corrId, entry.get("correlation_id"));
        }
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test
    void robustness_unknownCorrelationId_returnsEmptyList() {
        List<Map<String, Object>> result = tools.getObligationActivity(
                "corr-does-not-exist-" + java.util.UUID.randomUUID(), null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void robustness_limitEnforced() {
        String caseId = "oa-limit-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work", "Work", "APPEND", null, null, null, null, null, null);

        for (int i = 0; i < 10; i++) {
            tools.sendMessage(caseId + "/work", "agent-a", "STATUS",
                    "step " + i, corrId, null, null, null, null);
        }

        assertEquals(10, tools.getObligationActivity(corrId, null, null).size());
        assertEquals(3,  tools.getObligationActivity(corrId, null, 3).size());
    }

    @Test
    void robustness_includeContentSearchParam_hasNoEffect() {
        String caseId = "oa-compat-" + java.util.UUID.randomUUID();
        String corrId = "corr-" + java.util.UUID.randomUUID();

        tools.createChannel(caseId + "/work", "Work", "APPEND", null, null, null, null, null, null);
        tools.sendMessage(caseId + "/work", "agent-a", "COMMAND", "go", corrId, null, null, null, null);
        tools.sendMessage(caseId + "/work", "agent-a", "DONE",    "ok", corrId, null, null, null, null);

        // includeContentSearch is deprecated — all three call variants return the same result
        assertEquals(2, tools.getObligationActivity(corrId, null,  null).size());
        assertEquals(2, tools.getObligationActivity(corrId, true,  null).size());
        assertEquals(2, tools.getObligationActivity(corrId, false, null).size());
    }
}

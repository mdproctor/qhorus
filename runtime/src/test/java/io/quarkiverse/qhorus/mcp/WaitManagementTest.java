package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #39 — Wait management: cancel_wait, list_pending_waits, wait_for_reply cancellation detection.
 *
 * <p>
 * Three tools:
 * <ul>
 * <li>{@code cancel_wait(correlation_id)} — deletes the PendingReply row</li>
 * <li>{@code list_pending_waits()} — lists all PendingReply rows with channel name resolved</li>
 * <li>{@code wait_for_reply} modification — detects missing PendingReply and returns
 * {@code status="cancelled"} instead of timing out</li>
 * </ul>
 *
 * <p>
 * Same threading note as ApprovalGateTest: PendingReply state is set up directly via
 * messageService.registerPendingReply() to avoid CDI context propagation issues with
 * raw ExecutorService threads.
 *
 * <p>
 * Refs #39, Epic #36.
 */
@QuarkusTest
class WaitManagementTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @Inject
    ManagedExecutor executor;

    // -------------------------------------------------------------------------
    // Unit — cancel_wait
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void cancelWaitDeletesPendingReply() {
        tools.createChannel("wm-cancel-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-cancel-1").orElseThrow();
        messageService.registerPendingReply(corrId, ch.id, null, Instant.now().plusSeconds(60));

        QhorusMcpTools.CancelWaitResult result = tools.cancelWait(corrId);

        assertNotNull(result);
        assertEquals(corrId, result.correlationId());
        assertTrue(result.cancelled(), "cancel should succeed for existing pending wait");
    }

    @Test
    @TestTransaction
    void cancelWaitOnUnknownIdReturnsFalse() {
        String unknownId = UUID.randomUUID().toString();

        QhorusMcpTools.CancelWaitResult result = tools.cancelWait(unknownId);

        assertFalse(result.cancelled(), "cancel on unknown correlationId should return cancelled=false");
        assertNotNull(result.message(), "should include an informative message");
    }

    @Test
    @TestTransaction
    void cancelWaitRemovesFromList() {
        tools.createChannel("wm-cancel-2", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-cancel-2").orElseThrow();
        messageService.registerPendingReply(corrId, ch.id, null, Instant.now().plusSeconds(60));

        // Verify it's in the list
        assertTrue(tools.listPendingWaits().stream()
                .anyMatch(w -> corrId.equals(w.correlationId())));

        // Cancel it
        tools.cancelWait(corrId);

        // No longer in the list
        assertFalse(tools.listPendingWaits().stream()
                .anyMatch(w -> corrId.equals(w.correlationId())),
                "cancelled wait should not appear in list_pending_waits");
    }

    // -------------------------------------------------------------------------
    // Unit — list_pending_waits
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void listPendingWaitsShowsRegisteredWait() {
        tools.createChannel("wm-list-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-1").orElseThrow();
        messageService.registerPendingReply(corrId, ch.id, null, Instant.now().plusSeconds(60));

        List<QhorusMcpTools.PendingWaitSummary> waits = tools.listPendingWaits();
        assertTrue(waits.stream().anyMatch(w -> corrId.equals(w.correlationId())));
    }

    @Test
    @TestTransaction
    void listPendingWaitsResolvesChannelName() {
        tools.createChannel("wm-list-2", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-2").orElseThrow();
        messageService.registerPendingReply(corrId, ch.id, null, Instant.now().plusSeconds(60));

        QhorusMcpTools.PendingWaitSummary summary = tools.listPendingWaits().stream()
                .filter(w -> corrId.equals(w.correlationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected wait not found"));

        assertEquals("wm-list-2", summary.channelName());
    }

    @Test
    @TestTransaction
    void listPendingWaitsShowsTimeRemaining() {
        tools.createChannel("wm-list-3", "Test", null, null);
        String corrId = UUID.randomUUID().toString();
        var ch = channelService.findByName("wm-list-3").orElseThrow();
        messageService.registerPendingReply(corrId, ch.id, null, Instant.now().plusSeconds(90));

        QhorusMcpTools.PendingWaitSummary summary = tools.listPendingWaits().stream()
                .filter(w -> corrId.equals(w.correlationId()))
                .findFirst()
                .orElseThrow();

        assertTrue(summary.timeRemainingSeconds() > 0);
        assertNotNull(summary.expiresAt());
    }

    // -------------------------------------------------------------------------
    // Integration — wait_for_reply detects cancellation
    // ManagedExecutor propagates Quarkus CDI context so @Transactional works
    // on the background thread. waitForReply must start BEFORE cancel_wait
    // so the poll loop is running when cancellation happens.
    // -------------------------------------------------------------------------

    @Test
    void waitForReplyCancelledWhenPendingReplyDeleted() throws Exception {
        tools.createChannel("wm-wfr-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();

        // Start wait_for_reply in a CDI-aware background thread (30s timeout)
        Future<QhorusMcpTools.WaitResult> future = executor.submit(
                () -> tools.waitForReply("wm-wfr-1", corrId, 30, null));

        // Give wait_for_reply time to register its PendingReply and enter the poll loop
        Thread.sleep(400);

        // Delete the PendingReply (simulates what cancel_wait does)
        messageService.deletePendingReply(corrId);

        // Background thread should detect cancellation within one poll interval
        QhorusMcpTools.WaitResult result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.found(), "cancelled wait should not be found");
        assertFalse(result.timedOut(), "cancelled wait should not be a timeout");
        assertEquals("cancelled", result.status(),
                "status should be 'cancelled' when PendingReply is deleted mid-wait");
    }

    @Test
    void cancelWaitToolUnblocksWaitForReply() throws Exception {
        tools.createChannel("wm-wfr-2", "Test", null, null);
        String corrId = UUID.randomUUID().toString();

        // Start wait_for_reply in CDI-aware background thread
        Future<QhorusMcpTools.WaitResult> future = executor.submit(
                () -> tools.waitForReply("wm-wfr-2", corrId, 30, null));

        // Give it time to register and enter poll loop
        Thread.sleep(400);

        // Cancel via the tool
        tools.cancelWait(corrId);

        QhorusMcpTools.WaitResult result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.found());
        assertFalse(result.timedOut());
        assertEquals("cancelled", result.status());
    }

    // -------------------------------------------------------------------------
    // E2E — full wait management lifecycle
    // -------------------------------------------------------------------------

    @Test
    void e2eListWaitsAndCancelUnblocksAgent() throws Exception {
        tools.createChannel("wm-e2e-1", "Test", null, null);
        String corrId = UUID.randomUUID().toString();

        // 1. Agent calls waitForReply (in CDI-aware background thread, 60s timeout)
        Future<QhorusMcpTools.WaitResult> agentFuture = executor.submit(
                () -> tools.waitForReply("wm-e2e-1", corrId, 60, null));

        // Give it time to register and enter poll loop
        Thread.sleep(400);

        // 2. Human calls list_pending_waits — sees the blocked agent
        List<QhorusMcpTools.PendingWaitSummary> waits = tools.listPendingWaits();
        assertTrue(waits.stream().anyMatch(w -> corrId.equals(w.correlationId())),
                "human should see the pending wait");
        assertEquals("wm-e2e-1", waits.stream()
                .filter(w -> corrId.equals(w.correlationId()))
                .findFirst().orElseThrow().channelName());

        // 3. Human calls cancel_wait
        QhorusMcpTools.CancelWaitResult cancel = tools.cancelWait(corrId);
        assertTrue(cancel.cancelled());

        // 4. Pending wait no longer in list
        assertFalse(tools.listPendingWaits().stream()
                .anyMatch(w -> corrId.equals(w.correlationId())));

        // 5. Agent receives cancelled result
        QhorusMcpTools.WaitResult agentResult = agentFuture.get(5, TimeUnit.SECONDS);
        assertFalse(agentResult.found());
        assertFalse(agentResult.timedOut());
        assertEquals("cancelled", agentResult.status());
    }

    @Test
    @TestTransaction
    void e2eMultiplePendingWaitsCanBeSelectivelyCancelled() {
        tools.createChannel("wm-e2e-2", "Test", null, null);
        String corrId1 = UUID.randomUUID().toString();
        String corrId2 = UUID.randomUUID().toString();

        var ch = channelService.findByName("wm-e2e-2").orElseThrow();
        messageService.registerPendingReply(corrId1, ch.id, null, Instant.now().plusSeconds(60));
        messageService.registerPendingReply(corrId2, ch.id, null, Instant.now().plusSeconds(60));

        // Both visible
        List<QhorusMcpTools.PendingWaitSummary> waits = tools.listPendingWaits();
        assertTrue(waits.stream().anyMatch(w -> corrId1.equals(w.correlationId())));
        assertTrue(waits.stream().anyMatch(w -> corrId2.equals(w.correlationId())));

        // Cancel only corrId1
        tools.cancelWait(corrId1);

        List<QhorusMcpTools.PendingWaitSummary> after = tools.listPendingWaits();
        assertFalse(after.stream().anyMatch(w -> corrId1.equals(w.correlationId())),
                "cancelled wait should be gone");
        assertTrue(after.stream().anyMatch(w -> corrId2.equals(w.correlationId())),
                "uncancelled wait should still be present");
    }
}

package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #44 — Watchdog tools return informative errors when disabled (default).
 * Refs #44, Epic #36.
 */
@QuarkusTest
class WatchdogDisabledTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void registerWatchdogDisabledThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tools.registerWatchdog("BARRIER_STUCK", "test-channel", 300, null,
                        "alerts", "human"));
        assertTrue(ex.getMessage().toLowerCase().contains("watchdog"),
                "error should mention watchdog");
    }

    @Test
    @TestTransaction
    void listWatchdogsDisabledThrows() {
        assertThrows(IllegalStateException.class, () -> tools.listWatchdogs());
    }

    @Test
    @TestTransaction
    void deleteWatchdogDisabledThrows() {
        assertThrows(IllegalStateException.class,
                () -> tools.deleteWatchdog(java.util.UUID.randomUUID().toString()));
    }
}

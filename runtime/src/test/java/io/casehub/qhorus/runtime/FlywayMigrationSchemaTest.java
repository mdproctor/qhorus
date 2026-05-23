package io.casehub.qhorus.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that qhorus Flyway migrations produce the correct schema when run
 * against the combined qhorus + ledger migration locations.
 *
 * <p>Plain Java — no Quarkus CDI, no drop-and-create bypass. Mirrors the
 * production Flyway configuration: ledger migrations (classpath:db/ledger/migration)
 * run first, providing the real {@code ledger_entry} table that the qhorus
 * subclass join migration depends on.
 */
class FlywayMigrationSchemaTest {

    // Unique name prevents cross-run state sharing when JVM is reused across Surefire executions.
    private static final String JDBC_URL =
            "jdbc:h2:mem:flyway_schema_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void migrate() {
        // Run both migration locations together — Flyway sorts by version number.
        // Ledger migrations (V1000+) create ledger_entry before qhorus V2000 runs.
        // No manual ledger_entry creation needed: mirrors the production config exactly.
        Flyway.configure()
                .dataSource(JDBC_URL, "sa", "")
                .locations("classpath:db/qhorus/migration", "classpath:db/ledger/migration")
                .load()
                .migrate();
    }

    @Test
    void messageLedgerEntryTableExists() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var rs = conn.getMetaData().getTables(null, null, "MESSAGE_LEDGER_ENTRY", new String[]{"TABLE"})) {
            assertTrue(rs.next(),
                    "message_ledger_entry must exist — created by qhorus V2000 after ledger_entry (V1000) is in place");
        }
    }

    @Test
    void pendingReplyTableIsAbsent() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var rs = conn.getMetaData().getTables(null, null, "PENDING_REPLY", new String[]{"TABLE"})) {
            assertFalse(rs.next(),
                    "pending_reply was replaced by CommitmentStore and must not exist in the schema");
        }
    }

    @Test
    void commitmentTableExists() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var rs = conn.getMetaData().getTables(null, null, "COMMITMENT", new String[]{"TABLE"})) {
            assertTrue(rs.next(),
                    "commitment table must exist — it backs CommitmentStore and the full obligation lifecycle");
        }
    }
}

package io.casehub.qhorus.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that qhorus Flyway migrations produce the correct schema.
 * Plain Java — no Quarkus CDI, no drop-and-create bypass.
 */
class FlywayMigrationSchemaTest {

    // Unique name prevents cross-run state sharing when JVM is reused across Surefire executions.
    private static final String JDBC_URL =
            "jdbc:h2:mem:flyway_schema_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void migrate() throws Exception {
        // ledger_entry is owned by casehub-ledger and migrated before qhorus in production.
        // Create the minimal schema here to satisfy the FK in V1003.
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS ledger_entry (
                        id UUID NOT NULL,
                        CONSTRAINT pk_ledger_entry PRIMARY KEY (id)
                    )
                    """);
        }
        Flyway.configure()
                .dataSource(JDBC_URL, "sa", "")
                .locations("classpath:db/qhorus/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0") // Must be < V1 so V1 is executed, not skipped as the baseline
                .load()
                .migrate();
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

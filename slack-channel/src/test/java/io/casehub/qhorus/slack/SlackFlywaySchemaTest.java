package io.casehub.qhorus.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the slack-channel Flyway migrations produce the correct schema
 * when run against the combined qhorus + ledger migration locations.
 *
 * <p>Plain Java — no Quarkus CDI, no drop-and-create bypass. Runs V1–V22 from
 * casehub-qhorus, V1000+ from casehub-ledger, and V23–V24 from this module.
 */
class SlackFlywaySchemaTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:slack_flyway_schema_test_" + System.nanoTime()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(JDBC_URL, "sa", "")
                .locations("classpath:db/qhorus/migration", "classpath:db/ledger/migration")
                .load()
                .migrate();
    }

    @Test
    void v23_slack_bot_binding_table_exists() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var rs = conn.getMetaData().getTables(null, null, "SLACK_BOT_BINDING", new String[]{"TABLE"})) {
            assertThat(rs.next())
                    .as("slack_bot_binding table must exist — created by V23 migration")
                    .isTrue();
        }
    }

    @Test
    void v23_slack_bot_binding_has_unique_slack_channel_id() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            var rs = conn.getMetaData().getIndexInfo(null, null, "SLACK_BOT_BINDING", true, false);
            boolean found = false;
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                if ("SLACK_CHANNEL_ID".equalsIgnoreCase(colName)) {
                    found = true;
                }
            }
            rs.close();
            assertThat(found).as("UNIQUE index on slack_channel_id must exist").isTrue();
        }
    }
}

package dev.matthewsawyer.finance_dashboard.plaid;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaidSyncMigrationTests {
    @Test
    void onlyRecurringSyncStateBackfillsHistoryReadiness() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE plaid_items (item_id VARCHAR(255) PRIMARY KEY, "
                        + "transactions_cursor TEXT, recurring_synced_at TIMESTAMP WITH TIME ZONE)");
                statement.execute("INSERT INTO plaid_items VALUES "
                        + "('recurring-only', NULL, CURRENT_TIMESTAMP), "
                        + "('cursor-only', 'initial-cursor', NULL), "
                        + "('both', 'cursor', CURRENT_TIMESTAMP), ('neither', NULL, NULL)");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V19__plaid_sync_jobs.sql"));
            Map<String, Boolean> ready = new HashMap<>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT item_id, transaction_history_ready FROM plaid_items")) {
                while (rows.next()) ready.put(rows.getString(1), rows.getBoolean(2));
            }
            assertEquals(Map.of("recurring-only", true, "both", true, "cursor-only", false, "neither", false), ready);
        }
    }
}

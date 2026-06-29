package com.playdata.calen.ledger.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerAiAnalysisSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || (!productName.contains("MariaDB") && !productName.contains("MySQL"))) {
                return;
            }

            createTableIfMissing(connection);
            if (!hasTable(connection, "ledger_ai_analysis_histories")) {
                return;
            }

            addIndexIfMissing(connection, "idx_ledger_ai_history_owner_created",
                    "ALTER TABLE ledger_ai_analysis_histories ADD INDEX idx_ledger_ai_history_owner_created (owner_id, created_at, id)");
            addIndexIfMissing(connection, "idx_ledger_ai_history_owner_range",
                    "ALTER TABLE ledger_ai_analysis_histories ADD INDEX idx_ledger_ai_history_owner_range (owner_id, from_date, to_date)");
            addIndexIfMissing(connection, "idx_ledger_ai_history_owner_mode",
                    "ALTER TABLE ledger_ai_analysis_histories ADD INDEX idx_ledger_ai_history_owner_mode (owner_id, mode, period_type)");
        } catch (SQLException exception) {
            log.warn("Failed to verify ledger AI analysis schema: {}", exception.getMessage());
        }
    }

    private void createTableIfMissing(Connection connection) throws SQLException {
        if (hasTable(connection, "ledger_ai_analysis_histories")) {
            return;
        }
        executeQuietly("""
                CREATE TABLE ledger_ai_analysis_histories (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    owner_id BIGINT NOT NULL,
                    mode VARCHAR(20) NOT NULL,
                    period_type VARCHAR(20) NOT NULL,
                    comparison_preset VARCHAR(40) NULL,
                    status VARCHAR(20) NOT NULL,
                    from_date DATE NOT NULL,
                    to_date DATE NOT NULL,
                    compare_from_date DATE NULL,
                    compare_to_date DATE NULL,
                    model VARCHAR(80) NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    summary VARCHAR(2000) NULL,
                    error_message VARCHAR(1000) NULL,
                    request_payload_json LONGTEXT NULL,
                    result_json LONGTEXT NULL,
                    created_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (id)
                )
                """);
    }

    private void addIndexIfMissing(Connection connection, String indexName, String sql) throws SQLException {
        if (!hasIndex(connection, indexName)) {
            executeQuietly(sql);
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply ledger AI analysis schema update: {}", exception.getMessage());
        }
    }

    private boolean hasTable(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private boolean hasIndex(Connection connection, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, "ledger_ai_analysis_histories", false, false)) {
            while (indexes.next()) {
                String existingIndexName = indexes.getString("INDEX_NAME");
                if (existingIndexName != null && existingIndexName.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, "LEDGER_AI_ANALYSIS_HISTORIES", false, false)) {
            while (indexes.next()) {
                String existingIndexName = indexes.getString("INDEX_NAME");
                if (existingIndexName != null && existingIndexName.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }
}

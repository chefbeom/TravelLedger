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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(prefix = "app.schema.legacy-updaters", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerImageAnalysisRequestSchemaUpdater implements ApplicationRunner {

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
            if (!hasTable(connection, "ledger_image_analysis_requests")) {
                return;
            }

            addIndexIfMissing(connection, "idx_ledger_image_analysis_owner_created",
                    "ALTER TABLE ledger_image_analysis_requests ADD INDEX idx_ledger_image_analysis_owner_created (owner_id, created_at, id)");
            addIndexIfMissing(connection, "idx_ledger_image_analysis_owner_status",
                    "ALTER TABLE ledger_image_analysis_requests ADD INDEX idx_ledger_image_analysis_owner_status (owner_id, status, created_at)");
            addIndexIfMissing(connection, "idx_ledger_image_analysis_owner_type",
                    "ALTER TABLE ledger_image_analysis_requests ADD INDEX idx_ledger_image_analysis_owner_type (owner_id, document_type, created_at)");
        } catch (SQLException exception) {
            log.warn("Failed to verify ledger image analysis request schema: {}", exception.getMessage());
        }
    }

    private void createTableIfMissing(Connection connection) throws SQLException {
        if (hasTable(connection, "ledger_image_analysis_requests")) {
            return;
        }
        executeQuietly("""
                CREATE TABLE ledger_image_analysis_requests (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    owner_id BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    document_type VARCHAR(30) NOT NULL,
                    file_name VARCHAR(260) NULL,
                    content_type VARCHAR(120) NULL,
                    file_size_bytes BIGINT NOT NULL,
                    summary VARCHAR(500) NULL,
                    error_message VARCHAR(1000) NULL,
                    raw_text LONGTEXT NULL,
                    result_json LONGTEXT NULL,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    completed_at DATETIME(6) NULL,
                    cancelled_at DATETIME(6) NULL,
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
            log.warn("Failed to apply ledger image analysis request schema update: {}", exception.getMessage());
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
        if (hasIndexInTable(metaData, connection, "ledger_image_analysis_requests", indexName)) {
            return true;
        }
        return hasIndexInTable(metaData, connection, "LEDGER_IMAGE_ANALYSIS_REQUESTS", indexName);
    }

    private boolean hasIndexInTable(
            DatabaseMetaData metaData,
            Connection connection,
            String tableName,
            String indexName
    ) throws SQLException {
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
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

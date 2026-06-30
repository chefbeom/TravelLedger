package com.playdata.calen.account.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(prefix = "app.schema.legacy-updaters", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdGoalSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || (!productName.contains("MariaDB") && !productName.contains("MySQL"))) {
                return;
            }

            executeQuietly("""
                    CREATE TABLE IF NOT EXISTS household_goals (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        owner_id BIGINT NOT NULL,
                        title VARCHAR(120) NOT NULL,
                        target_amount_krw DECIMAL(19,2) NOT NULL,
                        current_amount_krw DECIMAL(19,2) NOT NULL,
                        due_date DATE NULL,
                        status VARCHAR(20) NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        created_at DATETIME(6) NOT NULL,
                        updated_at DATETIME(6) NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            addIndexIfMissing(connection, "idx_household_goals_owner_status", "ALTER TABLE household_goals ADD INDEX idx_household_goals_owner_status (owner_id, status, created_at, id)");
            addIndexIfMissing(connection, "idx_household_goals_owner_due", "ALTER TABLE household_goals ADD INDEX idx_household_goals_owner_due (owner_id, due_date)");
        } catch (SQLException exception) {
            log.warn("Failed to verify household goal schema: {}", exception.getMessage());
        }
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
            log.warn("Failed to apply household goal schema update: {}", exception.getMessage());
        }
    }

    private boolean hasIndex(Connection connection, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, "household_goals", false, false)) {
            while (indexes.next()) {
                String existingIndexName = indexes.getString("INDEX_NAME");
                if (existingIndexName != null && existingIndexName.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, "HOUSEHOLD_GOALS", false, false)) {
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
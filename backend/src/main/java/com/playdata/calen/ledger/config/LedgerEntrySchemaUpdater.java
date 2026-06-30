package com.playdata.calen.ledger.config;

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
public class LedgerEntrySchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || (!productName.contains("MariaDB") && !productName.contains("MySQL"))) {
                return;
            }

            if (!hasTable(connection, "ledger_entries")) {
                return;
            }

            addColumnIfMissing(connection, "foreign_currency_code", "ALTER TABLE ledger_entries ADD COLUMN foreign_currency_code VARCHAR(3) NULL");
            addColumnIfMissing(connection, "foreign_amount", "ALTER TABLE ledger_entries ADD COLUMN foreign_amount DECIMAL(18, 4) NULL");
            addColumnIfMissing(connection, "exchange_rate_to_krw", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_to_krw DECIMAL(18, 6) NULL");
            addColumnIfMissing(connection, "exchange_rate_date", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_date DATE NULL");
            addColumnIfMissing(connection, "exchange_rate_provider", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_provider VARCHAR(40) NULL");
            addColumnIfMissing(connection, "travel_plan_id", "ALTER TABLE ledger_entries ADD COLUMN travel_plan_id BIGINT NULL");
            addColumnIfMissing(connection, "travel_record_id", "ALTER TABLE ledger_entries ADD COLUMN travel_record_id BIGINT NULL");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_deleted_date_id",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_deleted_date_id (owner_id, deleted_at, entry_date, id)");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_deleted_type_date",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_deleted_type_date (owner_id, deleted_at, entry_type, entry_date)");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_title",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_title (owner_id, title)");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_memo",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_memo (owner_id, memo)");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_category_date",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_category_date (owner_id, category_group_id, category_detail_id, entry_date)");
            addIndexIfMissing(connection, "ledger_entries", "idx_ledger_entries_owner_payment_date",
                    "ALTER TABLE ledger_entries ADD INDEX idx_ledger_entries_owner_payment_date (owner_id, payment_method_id, entry_date)");
            addIndexIfMissing(connection, "category_groups", "idx_category_groups_owner_name",
                    "ALTER TABLE category_groups ADD INDEX idx_category_groups_owner_name (owner_id, name)");
            addIndexIfMissing(connection, "category_groups", "idx_category_groups_owner_active_type",
                    "ALTER TABLE category_groups ADD INDEX idx_category_groups_owner_active_type (owner_id, active, entry_type)");
            addIndexIfMissing(connection, "category_details", "idx_category_details_group_name",
                    "ALTER TABLE category_details ADD INDEX idx_category_details_group_name (group_id, name)");
            addIndexIfMissing(connection, "category_details", "idx_category_details_group_active",
                    "ALTER TABLE category_details ADD INDEX idx_category_details_group_active (group_id, active)");
            addIndexIfMissing(connection, "payment_methods", "idx_payment_methods_owner_name",
                    "ALTER TABLE payment_methods ADD INDEX idx_payment_methods_owner_name (owner_id, name)");
            addIndexIfMissing(connection, "payment_methods", "idx_payment_methods_owner_active",
                    "ALTER TABLE payment_methods ADD INDEX idx_payment_methods_owner_active (owner_id, active)");
        } catch (SQLException exception) {
            log.warn("Failed to verify ledger entry schema: {}", exception.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String columnName, String sql) throws SQLException {
        if (!hasColumn(connection, "ledger_entries", columnName)) {
            executeQuietly(sql);
        }
    }

    private void addIndexIfMissing(Connection connection, String tableName, String indexName, String sql) throws SQLException {
        if (hasTable(connection, tableName) && !hasIndex(connection, tableName, indexName)) {
            executeQuietly(sql);
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply ledger entry schema update: {}", exception.getMessage());
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

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }

    private boolean hasIndex(Connection connection, String tableName, String indexName) throws SQLException {
        if (hasIndexWithTableName(connection, tableName, indexName)) {
            return true;
        }
        return hasIndexWithTableName(connection, tableName.toUpperCase(), indexName);
    }

    private boolean hasIndexWithTableName(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
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

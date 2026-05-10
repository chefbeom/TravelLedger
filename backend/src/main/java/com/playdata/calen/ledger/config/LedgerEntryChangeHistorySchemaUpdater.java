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
public class LedgerEntryChangeHistorySchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || (!productName.contains("MariaDB") && !productName.contains("MySQL"))) {
                return;
            }

            if (!hasLedgerEntryChangeHistoryTable(connection)) {
                return;
            }

            executeQuietly("ALTER TABLE ledger_entry_change_histories MODIFY COLUMN summary VARCHAR(500) NOT NULL");
            executeQuietly("ALTER TABLE ledger_entry_change_histories MODIFY COLUMN before_snapshot_json LONGTEXT NOT NULL");
            executeQuietly("ALTER TABLE ledger_entry_change_histories MODIFY COLUMN after_snapshot_json LONGTEXT NOT NULL");
            if (!hasColumn(connection, "changes_json")) {
                executeQuietly("ALTER TABLE ledger_entry_change_histories ADD COLUMN changes_json LONGTEXT NULL");
            }
            executeQuietly("ALTER TABLE ledger_entry_change_histories MODIFY COLUMN changes_json LONGTEXT NULL");
        } catch (SQLException exception) {
            log.warn("Failed to verify ledger entry change history schema: {}", exception.getMessage());
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply ledger entry change history schema update: {}", exception.getMessage());
        }
    }

    private boolean hasLedgerEntryChangeHistoryTable(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "ledger_entry_change_histories", null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "LEDGER_ENTRY_CHANGE_HISTORIES", null)) {
            return tables.next();
        }
    }

    private boolean hasColumn(Connection connection, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "ledger_entry_change_histories", columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "LEDGER_ENTRY_CHANGE_HISTORIES", columnName.toUpperCase())) {
            return columns.next();
        }
    }
}

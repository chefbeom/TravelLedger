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

            if (!hasLedgerEntriesTable(connection)) {
                return;
            }

            addColumnIfMissing(connection, "foreign_currency_code", "ALTER TABLE ledger_entries ADD COLUMN foreign_currency_code VARCHAR(3) NULL");
            addColumnIfMissing(connection, "foreign_amount", "ALTER TABLE ledger_entries ADD COLUMN foreign_amount DECIMAL(18, 4) NULL");
            addColumnIfMissing(connection, "exchange_rate_to_krw", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_to_krw DECIMAL(18, 6) NULL");
            addColumnIfMissing(connection, "exchange_rate_date", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_date DATE NULL");
            addColumnIfMissing(connection, "exchange_rate_provider", "ALTER TABLE ledger_entries ADD COLUMN exchange_rate_provider VARCHAR(40) NULL");
            addColumnIfMissing(connection, "travel_plan_id", "ALTER TABLE ledger_entries ADD COLUMN travel_plan_id BIGINT NULL");
            addColumnIfMissing(connection, "travel_record_id", "ALTER TABLE ledger_entries ADD COLUMN travel_record_id BIGINT NULL");
        } catch (SQLException exception) {
            log.warn("Failed to verify ledger entry schema: {}", exception.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String columnName, String sql) throws SQLException {
        if (!hasColumn(connection, columnName)) {
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

    private boolean hasLedgerEntriesTable(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "ledger_entries", null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "LEDGER_ENTRIES", null)) {
            return tables.next();
        }
    }

    private boolean hasColumn(Connection connection, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "ledger_entries", columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "LEDGER_ENTRIES", columnName.toUpperCase())) {
            return columns.next();
        }
    }
}

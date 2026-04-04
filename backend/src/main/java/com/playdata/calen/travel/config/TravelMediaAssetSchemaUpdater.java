package com.playdata.calen.travel.config;

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
public class TravelMediaAssetSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasTravelMediaAssetTable(connection)) {
                return;
            }

            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName != null && (productName.contains("MariaDB") || productName.contains("MySQL"))) {
                executeQuietly("ALTER TABLE travel_media_assets ADD COLUMN IF NOT EXISTS gps_latitude DECIMAL(10,7) NULL");
                executeQuietly("ALTER TABLE travel_media_assets ADD COLUMN IF NOT EXISTS gps_longitude DECIMAL(10,7) NULL");
                executeQuietly("ALTER TABLE travel_media_assets ADD COLUMN IF NOT EXISTS representative_override BOOLEAN NOT NULL DEFAULT FALSE");
                executeQuietly("ALTER TABLE travel_media_assets ADD COLUMN IF NOT EXISTS gps_extracted_at DATETIME NULL");
                executeQuietly("ALTER TABLE travel_media_assets ADD INDEX IF NOT EXISTS idx_travel_media_assets_gps (gps_latitude, gps_longitude)");
                executeQuietly("ALTER TABLE travel_media_assets ADD INDEX IF NOT EXISTS idx_travel_media_assets_rep_override (representative_override)");
            }
        } catch (SQLException exception) {
            log.warn("Failed to verify travel media schema: {}", exception.getMessage());
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply travel media schema update: {}", exception.getMessage());
        }
    }

    private boolean hasTravelMediaAssetTable(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "travel_media_assets", null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "TRAVEL_MEDIA_ASSETS", null)) {
            return tables.next();
        }
    }
}

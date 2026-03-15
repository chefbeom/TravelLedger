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
public class TravelRouteSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasTravelRouteTable(connection)) {
                return;
            }

            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName != null && (productName.contains("MariaDB") || productName.contains("MySQL"))) {
                jdbcTemplate.execute("ALTER TABLE travel_route_segments MODIFY COLUMN route_path_json LONGTEXT NOT NULL");
                jdbcTemplate.execute("ALTER TABLE travel_route_segments ADD COLUMN IF NOT EXISTS line_color_hex VARCHAR(7) NULL");
                jdbcTemplate.execute("ALTER TABLE travel_route_segments ADD COLUMN IF NOT EXISTS line_style VARCHAR(20) NULL");
                jdbcTemplate.execute("ALTER TABLE travel_route_segments ADD COLUMN IF NOT EXISTS gpx_files_json LONGTEXT NULL");
            }
        } catch (SQLException exception) {
            log.warn("Failed to verify travel route schema: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Failed to update travel route schema: {}", exception.getMessage());
        }
    }

    private boolean hasTravelRouteTable(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "travel_route_segments", null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "TRAVEL_ROUTE_SEGMENTS", null)) {
            return tables.next();
        }
    }
}

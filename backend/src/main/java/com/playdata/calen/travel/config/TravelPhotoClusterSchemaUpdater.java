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
public class TravelPhotoClusterSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || (!productName.contains("MariaDB") && !productName.contains("MySQL"))) {
                return;
            }

            if (!hasTravelMediaAssetTable(connection)) {
                return;
            }

            executeQuietly("""
                    CREATE TABLE IF NOT EXISTS travel_photo_clusters (
                        id BIGINT NOT NULL,
                        owner_id BIGINT NOT NULL,
                        representative_media_id BIGINT NOT NULL,
                        representative_record_id BIGINT NOT NULL,
                        plan_id BIGINT NOT NULL,
                        plan_name VARCHAR(120) NOT NULL,
                        plan_color_hex VARCHAR(7) NOT NULL,
                        memory_date DATE NULL,
                        memory_time TIME NULL,
                        category VARCHAR(80) NULL,
                        title VARCHAR(200) NULL,
                        country VARCHAR(120) NULL,
                        region VARCHAR(120) NULL,
                        place_name VARCHAR(200) NULL,
                        latitude DECIMAL(10,7) NOT NULL,
                        longitude DECIMAL(10,7) NOT NULL,
                        photo_count INT NOT NULL,
                        memory_count INT NOT NULL,
                        max_distance_meters DECIMAL(10,2) NOT NULL,
                        representative_override BOOLEAN NOT NULL DEFAULT FALSE,
                        updated_at DATETIME NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            executeQuietly("ALTER TABLE travel_photo_clusters ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_owner (owner_id)");
            executeQuietly("ALTER TABLE travel_photo_clusters ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_rep_media (representative_media_id)");

            executeQuietly("""
                    CREATE TABLE IF NOT EXISTS travel_photo_cluster_members (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        owner_id BIGINT NOT NULL,
                        cluster_id BIGINT NOT NULL,
                        media_id BIGINT NOT NULL,
                        sort_order INT NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            executeQuietly("ALTER TABLE travel_photo_cluster_members ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_owner (owner_id)");
            executeQuietly("ALTER TABLE travel_photo_cluster_members ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_cluster (cluster_id)");
            executeQuietly("ALTER TABLE travel_photo_cluster_members ADD UNIQUE INDEX IF NOT EXISTS uq_travel_photo_cluster_members_cluster_media (cluster_id, media_id)");
        } catch (SQLException exception) {
            log.warn("Failed to verify travel photo cluster schema: {}", exception.getMessage());
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply travel photo cluster schema update: {}", exception.getMessage());
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

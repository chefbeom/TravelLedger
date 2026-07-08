package com.playdata.calen.travel.config;

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
public class TravelMapShareLinkSchemaUpdater implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (var connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName != null && (productName.contains("MariaDB") || productName.contains("MySQL"))) {
                executeQuietly("""
                        CREATE TABLE IF NOT EXISTS travel_map_share_links (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            owner_id BIGINT NOT NULL,
                            token VARCHAR(80) NOT NULL,
                            title VARCHAR(160) NULL,
                            plan_ids_json TEXT NOT NULL,
                            excluded_record_ids_json TEXT NOT NULL,
                            excluded_media_ids_json TEXT NOT NULL,
                            excluded_route_ids_json TEXT NOT NULL,
                            active BOOLEAN NOT NULL DEFAULT TRUE,
                            created_at DATETIME NOT NULL,
                            updated_at DATETIME NOT NULL,
                            PRIMARY KEY (id),
                            UNIQUE KEY uk_travel_map_share_links_token (token),
                            KEY idx_travel_map_share_links_owner (owner_id)
                        )
                        """);
            }
        } catch (Exception exception) {
            log.warn("Failed to verify travel map share link schema: {}", exception.getMessage());
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("Failed to apply travel map share link schema update: {}", exception.getMessage());
        }
    }
}

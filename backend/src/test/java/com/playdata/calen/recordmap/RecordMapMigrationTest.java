package com.playdata.calen.recordmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class RecordMapMigrationTest {
    @Test
    void freshSchemaPreservesOwnersSourceUniquenessAndCollectionMembership() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:record-map-migration;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("create table app_users(id bigint primary key)");
            sql.execute("insert into app_users values(1), (2)");
            var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261013_033__record_map.sql"));
            migration.populate(connection);
            migration.populate(connection); // No data reset on a repeat of this bootstrap slice.

            sql.execute("insert into record_map_collections(owner_id, name, color_hex, created_at) values(1, 'food', '#009E8C', current_timestamp)");
            sql.execute("insert into record_map_places(owner_id, name, latitude, longitude, source_travel_record_id, created_at, updated_at) values(1, 'cafe', 37.5, 127, 42, current_timestamp, current_timestamp)");
            sql.execute("insert into record_map_collection_places values(1, 1)");
            sql.execute("insert into record_map_visits(owner_id, place_id, title, visit_date, created_at) values(1, 1, 'visit', '2026-10-13', current_timestamp)");
            sql.execute("insert into record_map_photos(owner_id, visit_id, original_name, storage_path, content_type, file_size, created_at) values(1, 1, 'test.png', 'test-only.png', 'image/png', 1, current_timestamp)");
            sql.execute("insert into record_map_shares(owner_id, collection_id, token_hash, expires_at, created_at) values(1, 1, 'test-only-hash', current_timestamp, current_timestamp)");
            assertThatThrownBy(() -> sql.execute("insert into record_map_places(owner_id, name, latitude, longitude, source_travel_record_id, created_at, updated_at) values(1, 'duplicate', 37.5, 127, 42, current_timestamp, current_timestamp)"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.execute("delete from app_users where id = 1"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.execute("delete from record_map_collections where id = 1"))
                .isInstanceOf(SQLException.class);
            sql.execute("delete from record_map_shares where collection_id = 1");
            sql.execute("delete from record_map_collection_places where collection_id = 1");
            sql.execute("delete from record_map_collections where id = 1");
            try (var result = sql.executeQuery("select count(*) from record_map_photos")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var result = sql.executeQuery("select version from record_map_places where id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            var indexNames = new java.util.HashSet<String>();
            try (var indexes = connection.getMetaData().getIndexInfo(null, "PUBLIC", "RECORD_MAP_VISITS", false, false)) {
                while (indexes.next()) indexNames.add(indexes.getString("INDEX_NAME"));
            }
            assertThat(indexNames).contains("IDX_RECORD_MAP_VISIT_OWNER_PLACE_DATE");
        }
    }
}

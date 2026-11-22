package com.playdata.calen.common.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PerformanceIndexMigrationTest {
    private static final String MIGRATION = "db/migration/V20261122_063__performance_indexes.sql";

    private static final Map<String, List<String>> EXPECTED = Map.of(
            "idx_record_map_collection_owner", List.of("record_map_collections", "owner_id", "created_at", "id"),
            "idx_record_map_place_owner_updated", List.of("record_map_places", "owner_id", "updated_at", "id"),
            "idx_record_map_visit_owner_place_date", List.of("record_map_visits", "owner_id", "place_id", "visit_date", "id"),
            "idx_record_map_visit_owner_date", List.of("record_map_visits", "owner_id", "visit_date", "visit_time", "id"),
            "idx_record_map_photo_owner_visit", List.of("record_map_photos", "owner_id", "visit_id", "id"),
            "idx_record_map_share_owner_collection", List.of("record_map_shares", "owner_id", "collection_id"),
            "idx_ledger_adjustment_origin", List.of("ledger_entries", "owner_id", "adjustment_origin_id"),
            "idx_ledger_entries_owner_travel_record", List.of("ledger_entries", "owner_id", "travel_record_id"));

    @Test
    void freshFlywayDatabaseAppliesMigrationTwiceAndKeepsRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:perf-index-fresh;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("create table app_users(id bigint primary key)");
            sql.execute("insert into app_users values(1)");
            populate(connection, "db/migration/V20261013_033__record_map.sql");
            populate(connection, "db/migration/V20261111_058__record_map_undated_photos.sql");
            sql.execute("create table ledger_entries(id bigint primary key, owner_id bigint not null, adjustment_origin_id bigint, travel_record_id bigint)");
            sql.execute("insert into ledger_entries values(1, 1, 5, 7)");
            sql.execute("insert into record_map_places(owner_id, name, latitude, longitude, created_at, updated_at) values(1, 'cafe', 37.5, 127, current_timestamp, current_timestamp)");
            sql.execute("insert into record_map_visits(owner_id, place_id, title, visit_date, created_at) values(1, 1, 'visit', '2026-10-13', current_timestamp)");

            populate(connection, MIGRATION);
            populate(connection, MIGRATION);

            assertAllIndexes(connection);
            assertRows(connection);
        }
    }

    @Test
    void hibernateStyleDatabaseWithoutInlineIndexesGetsAllIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:perf-index-hibernate;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("create table record_map_collections(id bigint primary key, owner_id bigint not null, created_at timestamp)");
            sql.execute("create table record_map_places(id bigint primary key, owner_id bigint not null, updated_at timestamp)");
            sql.execute("create table record_map_visits(id bigint primary key, owner_id bigint not null, place_id bigint not null, visit_date date, visit_time time)");
            sql.execute("create table record_map_photos(id bigint primary key, owner_id bigint not null, visit_id bigint not null)");
            sql.execute("create table record_map_shares(id bigint primary key, owner_id bigint not null, collection_id bigint not null)");
            sql.execute("create table ledger_entries(id bigint primary key, owner_id bigint not null, adjustment_origin_id bigint, travel_record_id bigint)");
            sql.execute("insert into ledger_entries values(1, 1, 5, 7)");
            sql.execute("insert into record_map_places values(1, 1, current_timestamp)");
            sql.execute("insert into record_map_visits values(1, 1, 1, '2026-10-13', null)");

            populate(connection, MIGRATION);
            populate(connection, MIGRATION);

            assertAllIndexes(connection);
            assertRows(connection);
        }
    }

    private static void populate(Connection connection, String resource) {
        new ResourceDatabasePopulator(new ClassPathResource(resource)).populate(connection);
    }

    private static void assertRows(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
                var ledger = statement.executeQuery("select owner_id, adjustment_origin_id, travel_record_id from ledger_entries")) {
            assertThat(ledger.next()).isTrue();
            assertThat(ledger.getLong(1)).isEqualTo(1L);
            assertThat(ledger.getLong(2)).isEqualTo(5L);
            assertThat(ledger.getLong(3)).isEqualTo(7L);
            assertThat(ledger.next()).isFalse();
        }
        try (var statement = connection.createStatement();
                var visits = statement.executeQuery("select count(*) from record_map_visits where visit_date = '2026-10-13'")) {
            assertThat(visits.next()).isTrue();
            assertThat(visits.getInt(1)).isEqualTo(1);
        }
        try (var statement = connection.createStatement();
                var places = statement.executeQuery("select count(*) from record_map_places")) {
            assertThat(places.next()).isTrue();
            assertThat(places.getInt(1)).isEqualTo(1);
        }
    }

    private static void assertAllIndexes(Connection connection) throws Exception {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select index_name, table_name, column_name from information_schema.index_columns order by index_name, ordinal_position")) {
            while (rows.next()) {
                var columns = actual.computeIfAbsent(rows.getString(1).toLowerCase(), key -> new ArrayList<>());
                if (columns.isEmpty()) columns.add(rows.getString(2).toLowerCase());
                columns.add(rows.getString(3).toLowerCase());
            }
        }
        EXPECTED.forEach((name, expected) -> assertThat(actual.get(name)).as(name).isEqualTo(expected));
    }
}

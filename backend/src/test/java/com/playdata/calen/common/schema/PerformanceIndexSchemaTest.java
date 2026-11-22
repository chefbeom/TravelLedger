package com.playdata.calen.common.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false"
})
class PerformanceIndexSchemaTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void entityIndexesMatchFlywayNamesSoDdlAutoEnvironmentsGetThem() {
        assertIndex("record_map_collections", "idx_record_map_collection_owner", "owner_id", "created_at", "id");
        assertIndex("record_map_places", "idx_record_map_place_owner_updated", "owner_id", "updated_at", "id");
        assertIndex("record_map_visits", "idx_record_map_visit_owner_place_date", "owner_id", "place_id", "visit_date", "id");
        assertIndex("record_map_visits", "idx_record_map_visit_owner_date", "owner_id", "visit_date", "visit_time", "id");
        assertIndex("record_map_photos", "idx_record_map_photo_owner_visit", "owner_id", "visit_id", "id");
        assertIndex("record_map_shares", "idx_record_map_share_owner_collection", "owner_id", "collection_id");
        assertIndex("ledger_entries", "idx_ledger_adjustment_origin", "owner_id", "adjustment_origin_id");
        assertIndex("ledger_entries", "idx_ledger_entries_owner_travel_record", "owner_id", "travel_record_id");
    }

    private void assertIndex(String table, String index, String... columns) {
        Map<String, List<String>> byIndex = new LinkedHashMap<>();
        jdbc.query(
                "select index_name, column_name from information_schema.index_columns where upper(table_name) = upper(?) order by index_name, ordinal_position",
                rs -> {
                    byIndex.computeIfAbsent(rs.getString(1).toLowerCase(), key -> new ArrayList<>())
                            .add(rs.getString(2).toLowerCase());
                },
                table);
        assertThat(byIndex).as("indexes of " + table).containsKey(index);
        assertThat(byIndex.get(index)).as(index).containsExactly(columns);
    }
}

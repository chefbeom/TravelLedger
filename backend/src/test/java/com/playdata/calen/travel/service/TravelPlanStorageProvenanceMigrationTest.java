package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class TravelPlanStorageProvenanceMigrationTest {
    @Test void migrationPreservesExistingPlansAndCanBeRepeated() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:travel-origin-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table travel_plans(id bigint primary key, owner_id bigint not null)");
        jdbc.update("insert into travel_plans(id, owner_id) values (10, 1)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_049__travel_plan_storage_origin.sql"));
        migration.execute(ds); migration.execute(ds);
        assertThat(jdbc.queryForObject("select owner_id from travel_plans where id=10", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select storage_owner_history_json from travel_plans where id=10", String.class)).isNull();
    }
}

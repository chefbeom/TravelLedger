package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class TravelOptionalPeriodMigrationTest {
    @Test void migrationPreservesExistingDatesAndAllowsPeriodlessPlans() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:travel-optional-period;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
             var sql = connection.createStatement()) {
            sql.execute("create table travel_plans(id bigint primary key, start_date date not null, end_date date not null)");
            sql.execute("insert into travel_plans values(1, '2026-10-10', '2026-10-15')");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261112_060__travel_plan_optional_period.sql")).populate(connection);
            sql.execute("insert into travel_plans values(2, null, null)");
            try (var rows = sql.executeQuery("select start_date, end_date from travel_plans where id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getDate(1).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 10));
                assertThat(rows.getDate(2).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 15));
            }
            try (var rows = sql.executeQuery("select start_date, end_date from travel_plans where id=2")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getDate(1)).isNull();
                assertThat(rows.getDate(2)).isNull();
            }
        }
    }
}

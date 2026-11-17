package com.playdata.calen.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CalendarEventMigrationTest {
    @Test void migrationAddsPrivateEventsWithoutChangingExistingUsers() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:calendar-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table app_users (id bigint primary key)");
        jdbc.update("insert into app_users(id) values (?)", 42);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261114_061__calendar_events.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);
        jdbc.update("insert into calendar_events(owner_id,title,start_date,end_date,all_day,version) values(?,?,?,?,?,?)", 42, "test", "2024-02-28", "2024-03-01", true, 0);
        assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from calendar_events where owner_id=42", Integer.class)).isEqualTo(1);
    }

    @Test void mapGrantMigrationIsAdditiveAndRejectsDuplicateAccountSharePairs() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:calendar-map-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table app_users (id bigint primary key)");
        jdbc.update("insert into app_users(id) values (?)", 42);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261114_062__calendar_map_grants.sql"));
        migration.execute(dataSource); migration.execute(dataSource);
        jdbc.update("insert into calendar_map_grants(viewer_id, share_id, share_owner_id, selected) values(?,?,?,?)", 42, 10, 99, true);
        assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into calendar_map_grants(viewer_id, share_id, share_owner_id, selected) values(?,?,?,?)", 42, 10, 99, true)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

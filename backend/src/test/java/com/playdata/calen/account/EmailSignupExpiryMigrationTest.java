package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class EmailSignupExpiryMigrationTest {
    @Test void oldRowsDefaultToIneligibleAndRemainUnchanged() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:signup-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table app_users (id bigint primary key, login_id varchar(60), email varchar(254))");
        jdbc.execute("create table email_verification_tokens (id bigint primary key, user_id bigint, used_at timestamp, created_at timestamp)");
        jdbc.update("insert into app_users(id, login_id, email) values (1, 'old-user', 'old@example.test')");
        jdbc.update("insert into email_verification_tokens(id, user_id, created_at) values (1, 1, current_timestamp)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261101_039__email_signup_expiry.sql"));
        migration.execute(ds);
        migration.execute(ds);
        assertThat(jdbc.queryForObject("select expired_email_signup from app_users where id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select cleanup_eligible from email_verification_tokens where id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select login_id from app_users where id=1", String.class)).isEqualTo("old-user");
        assertThat(jdbc.queryForObject("select email from app_users where id=1", String.class)).isEqualTo("old@example.test");
        var pinMigration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261104_054__email_signup_pin_attempts.sql"));
        pinMigration.execute(ds);
        assertThat(jdbc.queryForObject("select signup_pin_failures from app_users where id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select signup_pin_blocked_until from app_users where id=1", java.sql.Timestamp.class)).isNull();
        jdbc.update("update app_users set signup_pin_failures=3 where id=1");
        pinMigration.execute(ds);
        assertThat(jdbc.queryForObject("select signup_pin_failures from app_users where id=1", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select email from app_users where id=1", String.class)).isEqualTo("old@example.test");
    }
}

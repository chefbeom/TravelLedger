package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;

class RememberMeBindingMigrationTest {
    @Test void existingCookiesAreBoundWithoutChangingSeriesOrTokenAndMigrationIsRepeatable() {
        var db = database();
        var jdbc = new JdbcTemplate(db);
        createLegacyTables(jdbc);
        jdbc.update("insert into app_users values (1, 'existing', true, true)");
        jdbc.update("insert into persistent_logins values ('existing', 'series-1', 'original-token', current_timestamp)");
        jdbc.update("insert into persistent_logins values ('unknown', 'series-2', 'unknown-token', current_timestamp)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261102_040__remember_me_user_binding.sql"));
        migration.execute(db);
        // Replaying the script after an orphan name is reused must not adopt its old cookie.
        jdbc.update("insert into app_users values (2, 'unknown', true, true)");
        migration.execute(db);
        assertThat(jdbc.queryForObject("select user_id from persistent_logins where series='series-1'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select token from persistent_logins where series='series-1'", String.class)).isEqualTo("original-token");
        assertThat(jdbc.queryForObject("select user_id from persistent_logins where series='series-2'", Long.class)).isNull();
        var repository = new BoundRememberMeTokenRepository(db, jdbc);
        var token = repository.getTokenForSeries("series-1");
        assertThat(BoundRememberMeTokenRepository.parseBoundUsername(token.getUsername())).hasValue(1L);
        assertThat(token.getTokenValue()).isEqualTo("original-token");
        assertThat(repository.getTokenForSeries("series-2")).isNull();
        jdbc.update("delete from persistent_logins where series='series-1'");
        assertThatThrownBy(() -> repository.updateToken("series-1", "late", new Date())).isInstanceOf(InvalidCookieException.class);
    }

    @Test void oldSchemaPreservesEvenAUsernameResemblingTheInternalIdPrefix() {
        var db = database();
        var jdbc = new JdbcTemplate(db);
        createLegacyTables(jdbc);
        jdbc.update("insert into app_users values (1, '{calen-user-id}:2', true, true)");
        jdbc.update("insert into app_users values (2, 'other', true, true)");
        jdbc.update("insert into persistent_logins values ('{calen-user-id}:2', 'own', 'a', current_timestamp)");
        jdbc.update("insert into persistent_logins values ('other', 'other', 'b', current_timestamp)");
        var repository = new BoundRememberMeTokenRepository(db, jdbc);
        assertThat(repository.isBoundSchemaAvailable()).isFalse();
        assertThatThrownBy(repository::requireBoundSchema).isInstanceOf(ServiceUnavailableException.class);
        repository.removeUserTokens("{calen-user-id}:2");
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where series='own'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where series='other'", Integer.class)).isEqualTo(1);
    }

    private static DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:remember-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void createLegacyTables(JdbcTemplate jdbc) {
        jdbc.execute("create table app_users (id bigint primary key, login_id varchar(60), active boolean, email_verified boolean)");
        jdbc.execute("create table persistent_logins (username varchar(64), series varchar(64) primary key, token varchar(64), last_used timestamp)");
    }
}

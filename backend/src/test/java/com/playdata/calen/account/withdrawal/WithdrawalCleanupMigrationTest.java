package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WithdrawalCleanupMigrationTest {
    @Test void createsAStandaloneIdempotentQueueSchemaWithAUniqueRequestKey() {
        DriverManagerDataSource db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:withdrawal-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261101_036__withdrawal_cleanup_tasks.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        JdbcTemplate jdbc = new JdbcTemplate(db);
        String request = UUID.randomUUID().toString();
        String insert = """
                insert into account_withdrawal_cleanup_tasks
                (id, request_id, kind, task_key_hash, payload_hash, encrypted_payload, state,
                 attempts, next_attempt_at, created_at, updated_at)
                values (?, ?, 'KAKAO_UNLINK', ?, ?, 'encrypted', 'PENDING', 0,
                        current_timestamp, current_timestamp, current_timestamp)
                """;
        String key = "k".repeat(43);
        jdbc.update(insert, UUID.randomUUID().toString(), request, key, "p".repeat(43));
        assertThat(jdbc.queryForObject("select version from account_withdrawal_cleanup_tasks", Long.class)).isZero();
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), request, key, "p".repeat(43)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("select count(*) from account_withdrawal_cleanup_tasks", Integer.class)).isEqualTo(1);
    }

    @Test void withdrawalRequestUniquenessPreventsTwoLiveRequestsForOneAccount() {
        DriverManagerDataSource db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:withdrawal-request-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261101_037__withdrawal_requests.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        JdbcTemplate jdbc = new JdbcTemplate(db);
        String insert = """
                insert into account_withdrawal_requests (id, user_id, state, requested_at, updated_at)
                values (?, ?, 'IN_PROGRESS', current_timestamp, current_timestamp)
                """;
        String first = UUID.randomUUID().toString();
        jdbc.update(insert, first, 42L);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), 42L)).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("update account_withdrawal_requests set user_id = null where id = ?", first);
        jdbc.update(insert, UUID.randomUUID().toString(), 42L);
        assertThat(jdbc.queryForObject("select count(*) from account_withdrawal_requests", Integer.class)).isEqualTo(2);
    }

    @Test void createsRecoveryChallengeSchemaWithUniqueTokensAndUserReference() {
        DriverManagerDataSource db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:withdrawal-challenge-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute("create table app_users (id bigint primary key)");
        jdbc.update("insert into app_users(id) values (42)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261101_038__withdrawal_email_challenges.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        String sql = """
                insert into withdrawal_email_challenges (user_id, token_hash, email_hash, created_at, expires_at)
                values (42, ?, ?, current_timestamp, current_timestamp)
                """;
        jdbc.update(sql, "a".repeat(64), "b".repeat(64));
        assertThatThrownBy(() -> jdbc.update(sql, "a".repeat(64), "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_email_challenges", Integer.class)).isEqualTo(1);
    }

    @Test void recoveryPinMigrationPreservesExistingChallengesAndIsIdempotent() {
        DriverManagerDataSource db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:withdrawal-pin-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute("create table app_users (id bigint primary key)");
        jdbc.update("insert into app_users(id) values (42)");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261101_038__withdrawal_email_challenges.sql")), db);
        jdbc.update("insert into withdrawal_email_challenges (user_id, token_hash, email_hash, created_at, expires_at) "
                + "values (42, ?, ?, current_timestamp, current_timestamp)", "a".repeat(64), "b".repeat(64));
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261105_055__withdrawal_recovery_pin_attempts.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        assertThat(jdbc.queryForObject("select pin_failures from withdrawal_email_challenges", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select pin_blocked_until from withdrawal_email_challenges", java.sql.Timestamp.class)).isNull();
    }
}

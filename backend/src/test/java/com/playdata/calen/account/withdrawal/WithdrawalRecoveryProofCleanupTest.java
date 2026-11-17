package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WithdrawalRecoveryProofCleanupTest {
    private JdbcTemplate jdbc;
    private WithdrawalRecoveryProofCleanup cleanup;

    @BeforeEach void setup() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:proof-cleanup-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table withdrawal_email_challenges (id bigint primary key, expires_at timestamp not null, "
                + "pin_failures int not null, pin_window_started_at timestamp, pin_blocked_until timestamp)");
        cleanup = new WithdrawalRecoveryProofCleanup(jdbc);
    }

    @Test void removesOnlyExpiredProofsWhosePinGuardHasExpired() {
        LocalDateTime now = LocalDateTime.now();
        insert(1, now.minusMinutes(1), 0, null, null);
        insert(2, now.plusMinutes(1), 0, null, null);
        insert(3, now.minusMinutes(1), 2, now.minusMinutes(5), null);
        insert(4, now.minusMinutes(1), 5, now.minusMinutes(30), now.plusMinutes(5));
        insert(5, now.minusMinutes(1), 1, now.minusMinutes(30), null);
        insert(6, now.minusMinutes(1), 2, null, null); // inconsistent: retain for investigation
        cleanup.removeExpiredProofs();
        assertThat(jdbc.queryForList("select id from withdrawal_email_challenges order by id", Long.class))
                .containsExactly(2L, 3L, 4L, 6L);
    }

    @Test void limitsEachCleanupCycle() {
        LocalDateTime expired = LocalDateTime.now().minusHours(1);
        for (long id = 1; id <= 251; id++) insert(id, expired, 0, null, null);
        cleanup.removeExpiredProofs();
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_email_challenges", Integer.class)).isEqualTo(1);
        cleanup.removeExpiredProofs();
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_email_challenges", Integer.class)).isZero();
    }

    private void insert(long id, LocalDateTime expiry, int failures, LocalDateTime started, LocalDateTime blocked) {
        jdbc.update("insert into withdrawal_email_challenges values (?, ?, ?, ?, ?)", id, expiry, failures, started, blocked);
    }
}

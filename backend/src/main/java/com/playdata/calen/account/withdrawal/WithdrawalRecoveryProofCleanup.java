package com.playdata.calen.account.withdrawal;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Discards expired recovery proofs without resetting an active PIN lockout. */
@Service
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class WithdrawalRecoveryProofCleanup {
    private static final int BATCH_SIZE = 250;
    private static final String ELIGIBLE = """
            expires_at <= ? and pin_failures between 0 and 5
            and (pin_blocked_until is null or pin_blocked_until <= ?)
            and ((pin_failures = 0 and pin_window_started_at is null)
                or (pin_window_started_at is not null and pin_window_started_at <= ?))
            """;
    private final JdbcTemplate jdbc;

    public WithdrawalRecoveryProofCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    @Transactional
    public void removeExpiredProofs() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp",
                (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new IllegalStateException("Recovery proof cleanup clock unavailable.");
        LocalDateTime expiredWindow = now.minusMinutes(15);
        List<Long> candidates = jdbc.queryForList("select id from withdrawal_email_challenges where " + ELIGIBLE
                + " order by expires_at, id limit ?", Long.class, now, now, expiredWindow, BATCH_SIZE);
        for (Long id : candidates) {
            // A reissue or PIN check may have changed a row while this worker was waiting.
            jdbc.update("delete from withdrawal_email_challenges where id = ? and " + ELIGIBLE,
                    id, now, now, expiredWindow);
        }
    }
}

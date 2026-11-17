package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account.unknown-login-audit-anonymization.enabled=false",
        "app.drive.direct-share-audit-anonymization.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:unknown-login-privacy-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class UnknownLoginAuditPrivacyBackfillIntegrationTest {
    @Autowired UnknownLoginAuditPrivacyBackfill backfill;
    @Autowired LoginAuditLogRepository logs;
    @Autowired AppUserRepository users;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void clearIsolatedAuditFixtures() { logs.deleteAll(); }

    @Test void batchesAnonymizeOnlyUnknownFailuresAndDoNotStarveBehindKnownAccounts() {
        AppUser owner = user();
        LocalDateTime time = LocalDateTime.of(2025, 3, 4, 5, 6);
        var first = historical("unknown-" + UUID.randomUUID(), LoginAuditStatus.BAD_CREDENTIALS, null, time);
        var known = historical(owner.getLoginId().toUpperCase(Locale.ROOT), LoginAuditStatus.BAD_CREDENTIALS, null, time);
        var second = historical("unknown-" + UUID.randomUUID(), LoginAuditStatus.BAD_SECONDARY_PIN, null, time);
        var linked = historical("old-alias-" + UUID.randomUUID(), LoginAuditStatus.BLOCKED, owner, time);
        var success = historical("unknown-" + UUID.randomUUID(), LoginAuditStatus.SUCCESS, null, time);

        assertThat(backfill.classifyBatch(2)).isEqualTo(2);
        assertAnonymized(first, time);
        var preserved = logs.findById(known.getId()).orElseThrow();
        assertThat(preserved.getLoginId()).isEqualTo(known.getLoginId());
        assertThat(preserved.getClientIp()).isEqualTo("192.0.2.33");
        assertThat(preserved.getUserAgent()).isEqualTo("agent");
        assertThat(preserved.isUnknownFailurePrivacyProcessed()).isTrue();
        assertThat(backfill.classifyBatch(2)).isEqualTo(1);
        assertAnonymized(second, time);
        assertThat(backfill.classifyBatch(2)).isZero();
        assertThat(logs.findById(linked.getId()).orElseThrow().getClientIp()).isEqualTo("192.0.2.33");
        assertThat(logs.findById(success.getId()).orElseThrow().getClientIp()).isEqualTo("192.0.2.33");

        // Simulate a late insert from an old node which does not write the new flag.
        var late = historical("late-" + UUID.randomUUID(), LoginAuditStatus.BLOCKED, null, time);
        assertThat(backfill.classifyBatch(2)).isEqualTo(1);
        assertAnonymized(late, time);
        assertThatThrownBy(() -> backfill.classifyBatch(251)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rollbackRestoresIdentifiersAndTheUnprocessedFlag() {
        var row = historical("rollback-" + UUID.randomUUID(), LoginAuditStatus.BAD_CREDENTIALS, null, LocalDateTime.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            backfill.classifyBatch(1);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        var restored = logs.findById(row.getId()).orElseThrow();
        assertThat(restored.getLoginId()).isEqualTo(row.getLoginId());
        assertThat(restored.getClientIp()).isEqualTo("192.0.2.33");
        assertThat(restored.isUnknownFailurePrivacyProcessed()).isFalse();
    }

    @Test void concurrentWorkersCanSafelyRetryTheSameUnprocessedRows() throws Exception {
        var first = historical("worker-a-" + UUID.randomUUID(), LoginAuditStatus.BAD_CREDENTIALS, null, LocalDateTime.now());
        var second = historical("worker-b-" + UUID.randomUUID(), LoginAuditStatus.BLOCKED, null, LocalDateTime.now());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> backfill.classifyBatch(2));
            var b = pool.submit(() -> backfill.classifyBatch(2));
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            backfill.classifyBatch(2);
            for (var original : List.of(first, second)) {
                var cleaned = logs.findById(original.getId()).orElseThrow();
                assertThat(cleaned.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
                assertThat(cleaned.isUnknownFailurePrivacyProcessed()).isTrue();
            }
        } finally { pool.shutdownNow(); }
    }

    private void assertAnonymized(LoginAuditLog original, LocalDateTime time) {
        var row = logs.findById(original.getId()).orElseThrow();
        assertThat(row.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(row.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(row.getUserAgent()).isNull();
        assertThat(row.getDetail()).isNull();
        assertThat(row.getStatus()).isEqualTo(original.getStatus());
        assertThat(row.getAttemptedAt()).isEqualTo(time);
        assertThat(row.isUnknownFailurePrivacyProcessed()).isTrue();
    }

    private AppUser user() {
        var user = new AppUser(); user.setLoginId("known-" + UUID.randomUUID()); user.setDisplayName("Known");
        return users.saveAndFlush(user);
    }

    private LoginAuditLog historical(String login, LoginAuditStatus status, AppUser user, LocalDateTime time) {
        var log = new LoginAuditLog(); log.setLoginId(login); log.setClientIp("192.0.2.33");
        log.setUserAgent("agent"); log.setDetail("private"); log.setStatus(status); log.setAppUser(user);
        log.setSuccess(status.isSuccess()); log.setAttemptedAt(time);
        return logs.saveAndFlush(log);
    }
}

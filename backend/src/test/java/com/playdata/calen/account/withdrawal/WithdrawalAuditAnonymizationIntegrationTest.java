package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.common.exception.BadRequestException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.account.unknown-login-audit-anonymization.enabled=false",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-audit-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class WithdrawalAuditAnonymizationIntegrationTest {
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository users;
    @Autowired LoginAuditLogService audit;
    @Autowired LoginAuditLogRepository logs;
    @Autowired WithdrawalAuditAnonymizer anonymizer;
    @Autowired UnknownLoginAuditBackfill unknownBackfill;
    @Autowired AccountWithdrawalStartService start;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    @BeforeEach void createEmbeddingOutboxSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void withdrawalPreservesEventTypesAndTimesButRemovesActorAndTargetIdentifiers() {
        AppUser owner = user(), other = user();
        audit.record(owner.getLoginId(), "192.0.2.10", "owner-agent", LoginAuditStatus.SUCCESS, "owner details", owner);
        audit.record(owner.getLoginId().toUpperCase(Locale.ROOT), "192.0.2.11", "failed-agent", LoginAuditStatus.BAD_CREDENTIALS, "failed", null);
        audit.record(owner.getLoginId(), "192.0.2.12", "admin-agent", LoginAuditStatus.ADMIN_ACTION,
                "DATA_RESTORE:private-file.sql", owner);
        audit.record(owner.getLoginId(), "192.0.2.13", "admin-agent", LoginAuditStatus.ADMIN_ACTION,
                "UNKNOWN_USER_TEXT:private", owner);
        audit.record(other.getLoginId(), "192.0.2.20", "other-agent", LoginAuditStatus.ADMIN_ACTION,
                "USER_ACTIVE_UPDATE:userId=" + owner.getId() + ",active=true", other);
        String unrelatedDetail = "USER_ACTIVE_UPDATE:userId=" + owner.getId() + "0,active=true";
        var historicalOtherTarget = new LoginAuditLog();
        historicalOtherTarget.setLoginId(other.getLoginId()); historicalOtherTarget.setClientIp("192.0.2.21");
        historicalOtherTarget.setUserAgent("other-agent"); historicalOtherTarget.setStatus(LoginAuditStatus.ADMIN_ACTION);
        historicalOtherTarget.setSuccess(true); historicalOtherTarget.setAppUser(other); historicalOtherTarget.setDetail(unrelatedDetail);
        logs.saveAndFlush(historicalOtherTarget);
        List<LoginAuditLog> before = logs.findAll();
        var byId = before.stream().collect(java.util.stream.Collectors.toMap(LoginAuditLog::getId, log -> log));

        start.start(owner.getId(), "12345678", Map.of(), Map.of());

        for (LoginAuditLog original : before.stream().filter(log -> log.getClientIp().startsWith("192.0.2.1")).toList()) {
            LoginAuditLog cleaned = logs.findById(original.getId()).orElseThrow();
            assertThat(cleaned.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(cleaned.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(cleaned.getUserAgent()).isNull();
            assertThat(cleaned.getAppUser()).isNull();
            assertThat(cleaned.getStatus()).isEqualTo(byId.get(cleaned.getId()).getStatus());
            assertThat(cleaned.getAttemptedAt()).isEqualTo(byId.get(cleaned.getId()).getAttemptedAt());
            assertThat(cleaned.getDetail()).isEqualTo(original.getClientIp().equals("192.0.2.12") ? "DATA_RESTORE" : null);
        }
        var targetAction = logs.findAll().stream().filter(log -> "192.0.2.20".equals(log.getClientIp())).findFirst().orElseThrow();
        assertThat(targetAction.getLoginId()).isEqualTo(other.getLoginId());
        assertThat(targetAction.getUserAgent()).isEqualTo("other-agent");
        assertThat(targetAction.getDetail()).isEqualTo("USER_ACTIVE_UPDATE");
        assertThat(logs.findAll().stream().filter(log -> "192.0.2.21".equals(log.getClientIp())).findFirst().orElseThrow().getDetail())
                .isEqualTo(unrelatedDetail);

        // Failed attempts and another administrator's action after deactivation must not recreate the identity.
        var beforeLateIds = logs.findAll().stream().map(LoginAuditLog::getId).collect(java.util.stream.Collectors.toSet());
        audit.record(owner.getLoginId().toUpperCase(Locale.ROOT), "192.0.2.30", "late-agent", LoginAuditStatus.BAD_CREDENTIALS, "late", null);
        audit.record(other.getLoginId(), "192.0.2.31", "other-agent", LoginAuditStatus.ADMIN_ACTION,
                "ADMIN_ROLE_GRANT:userId=" + owner.getId(), other);
        var lateLogin = logs.findAll().stream().filter(log -> !beforeLateIds.contains(log.getId())
                && log.getStatus() == LoginAuditStatus.BAD_CREDENTIALS).findFirst().orElseThrow();
        assertThat(lateLogin).satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo(LoginAuditStatus.BAD_CREDENTIALS);
            assertThat(log.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(log.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(log.getUserAgent()).isNull();
        });
        var lateTarget = logs.findAll().stream().filter(log -> "192.0.2.31".equals(log.getClientIp())).findFirst().orElseThrow();
        assertThat(lateTarget.getDetail()).isEqualTo("ADMIN_ROLE_GRANT");
    }

    @Test void anonymizationCannotRunForAnActiveUserAndRollsBackWithWithdrawal() {
        AppUser owner = user();
        audit.record(owner.getLoginId(), "192.0.2.40", "agent", LoginAuditStatus.SUCCESS, "detail", owner);
        Long logId = logs.findAll().stream().filter(log -> "192.0.2.40".equals(log.getClientIp())).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> anonymizer.anonymize(owner.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            AppUser locked = users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
            locked.setActive(false); users.saveAndFlush(locked);
            requests.saveAndFlush(request(owner.getId()));
            anonymizer.anonymize(owner.getId());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(logs.findById(logId).orElseThrow().getLoginId()).isEqualTo(owner.getLoginId());
        assertThat(logs.findById(logId).orElseThrow().getClientIp()).isEqualTo("192.0.2.40");
    }

    @Test void unknownLoginFailuresAreAnonymousWhileKnownActiveAccountFailuresRemainAuditable() {
        AppUser existing = user();
        var previousIds = logs.findAll().stream().map(LoginAuditLog::getId).collect(java.util.stream.Collectors.toSet());
        for (var status : List.of(LoginAuditStatus.BAD_CREDENTIALS, LoginAuditStatus.BAD_SECONDARY_PIN, LoginAuditStatus.BLOCKED)) {
            audit.record("unknown-" + UUID.randomUUID(), "192.0.2.70", "untrusted-agent", status, "untrusted detail", null);
        }
        String knownId = existing.getLoginId().toUpperCase(Locale.ROOT);
        audit.record(knownId, "192.0.2.71", "known-agent", LoginAuditStatus.BAD_CREDENTIALS, "known failure", null);
        var created = logs.findAll().stream().filter(log -> !previousIds.contains(log.getId())).toList();
        assertThat(created).hasSize(4);
        assertThat(created.stream().filter(log -> AuditLogAnonymization.ANONYMOUS.equals(log.getLoginId())).toList())
                .hasSize(3).allSatisfy(log -> {
                    assertThat(log.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
                    assertThat(log.getUserAgent()).isNull();
                    assertThat(log.getDetail()).isNull();
                    assertThat(log.isSuccess()).isFalse();
                    assertThat(log.isUnknownFailurePrivacyProcessed()).isTrue();
                });
        var known = created.stream().filter(log -> "192.0.2.71".equals(log.getClientIp())).findFirst().orElseThrow();
        assertThat(known.getLoginId()).isEqualTo(knownId);
        assertThat(known.getUserAgent()).isEqualTo("known-agent");
        assertThat(known.getDetail()).isEqualTo("known failure");
    }

    @Test void historicalUnknownFailuresCanBeAnonymizedInBoundedResumableBatches() {
        AppUser existing = user();
        LocalDateTime attemptedAt = LocalDateTime.of(2025, 3, 4, 5, 6, 7);
        var first = historical("never-" + UUID.randomUUID(), LoginAuditStatus.BAD_CREDENTIALS, null, attemptedAt);
        var known = historical(existing.getLoginId().toUpperCase(Locale.ROOT), LoginAuditStatus.BAD_CREDENTIALS, null, attemptedAt);
        var second = historical("never-" + UUID.randomUUID(), LoginAuditStatus.BAD_SECONDARY_PIN, null, attemptedAt);
        var linked = historical("old-alias-" + UUID.randomUUID(), LoginAuditStatus.BAD_CREDENTIALS, existing, attemptedAt);
        var third = historical("never-" + UUID.randomUUID(), LoginAuditStatus.BLOCKED, null, attemptedAt);
        var success = historical("success-" + UUID.randomUUID(), LoginAuditStatus.SUCCESS, null, attemptedAt);
        long cursor = first.getId() - 1;

        var firstBatch = unknownBackfill.anonymizeBatch(cursor, 2);
        assertThat(firstBatch.anonymizedCount()).isEqualTo(2);
        assertThat(firstBatch.lastId()).isEqualTo(second.getId());
        assertThat(logs.findById(third.getId()).orElseThrow().getClientIp()).isEqualTo("192.0.2.81");
        var secondBatch = unknownBackfill.anonymizeBatch(firstBatch.lastId(), 2);
        assertThat(secondBatch.anonymizedCount()).isEqualTo(1);
        assertThat(secondBatch.lastId()).isEqualTo(third.getId());
        assertThat(unknownBackfill.anonymizeBatch(secondBatch.lastId(), 2).anonymizedCount()).isZero();
        assertThat(unknownBackfill.anonymizeBatch(cursor, 2).anonymizedCount()).isZero();

        for (var original : List.of(first, second, third)) {
            var cleaned = logs.findById(original.getId()).orElseThrow();
            assertThat(cleaned.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(cleaned.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
            assertThat(cleaned.getUserAgent()).isNull();
            assertThat(cleaned.getDetail()).isNull();
            assertThat(cleaned.getStatus()).isEqualTo(original.getStatus());
            assertThat(cleaned.getAttemptedAt()).isEqualTo(attemptedAt);
        }
        for (var original : List.of(known, linked, success)) {
            assertThat(logs.findById(original.getId()).orElseThrow().getClientIp()).isEqualTo("192.0.2.81");
        }
        assertThatThrownBy(() -> unknownBackfill.anonymizeBatch(0, 251)).isInstanceOf(BadRequestException.class);
    }

    @Test void aConcurrentLateAuditWaitsAndIsAnonymizedAfterTheWithdrawalCommits() throws Exception {
        AppUser owner = user();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
                current.setActive(false); users.saveAndFlush(current);
                requests.saveAndFlush(request(owner.getId()));
                anonymizer.anonymize(owner.getId());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var late = pool.submit(() -> audit.record(owner.getLoginId(), "192.0.2.50", "late-agent",
                    LoginAuditStatus.SUCCESS, "late", owner));
            assertThatThrownBy(() -> late.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            withdrawal.get(5, TimeUnit.SECONDS);
            late.get(5, TimeUnit.SECONDS);
            assertThat(logs.findAll()).noneMatch(log -> "192.0.2.50".equals(log.getClientIp()));
            assertThat(logs.findAll()).anyMatch(log -> AuditLogAnonymization.ANONYMOUS.equals(log.getLoginId())
                    && AuditLogAnonymization.ANONYMOUS.equals(log.getClientIp()) && log.getStatus() == LoginAuditStatus.SUCCESS);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser user() {
        return appUsers.registerUser("audit" + UUID.randomUUID().toString().replace("-", ""),
                "테스트 회원", "test-password", "12345678");
    }

    private LoginAuditLog historical(String loginId, LoginAuditStatus status, AppUser actor, LocalDateTime at) {
        var log = new LoginAuditLog();
        log.setLoginId(loginId);
        log.setClientIp("192.0.2.81");
        log.setUserAgent("historical-agent");
        log.setDetail("historical-detail");
        log.setStatus(status);
        log.setSuccess(status.isSuccess());
        log.setAppUser(actor);
        log.setAttemptedAt(at);
        return logs.saveAndFlush(log);
    }

    private AccountWithdrawalRequest request(Long id) {
        var request = new AccountWithdrawalRequest();
        request.setId(UUID.randomUUID().toString());
        request.setUserId(id);
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
        request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        return request;
    }
}

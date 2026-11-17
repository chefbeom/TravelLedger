package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.EmailVerificationToken;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.EmailSignupExpiryService;
import com.playdata.calen.account.service.EmailSignupExpiryWorker;
import com.playdata.calen.account.service.EmailVerificationSender;
import com.playdata.calen.account.service.EmailVerificationService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.account.withdrawal.AuditLogAnonymization;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.service.DriveAdminService;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:signup-expiry-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.auth.email-verification.base-url=https://app.example.test"
})
class EmailSignupExpiryIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired EmailVerificationService signup;
    @Autowired EmailSignupExpiryService expiry;
    @Autowired EmailSignupExpiryWorker worker;
    @Autowired AppUserService accounts;
    @Autowired AdminService admins;
    @Autowired DriveAdminService driveAdmins;
    @Autowired LoginAuditLogService audit;
    @Autowired LoginAuditLogRepository logs;
    @Autowired SocialAccountRepository socialAccounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean EmailVerificationSender sender;

    @Test void aCachedSignupTokenCannotUndoAdministratorCancellation() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        AppUser pending = start(id, email);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), url.capture(), any());
        String raw = url.getValue().split("#verify-email/")[1];
        EmailVerificationToken token = openToken(pending.getId());
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            requestEntityManager.find(AppUser.class, pending.getId());
            EmailVerificationToken stale = requestEntityManager.find(EmailVerificationToken.class, token.getId());
            pool.submit(() -> admins.updateUserActive(-1L, pending.getId(), false)).get(5, TimeUnit.SECONDS);
            assertThat(stale.getUsedAt()).isNull();
            assertThatThrownBy(() -> signup.verify(raw, "12345678")).isInstanceOf(BadRequestException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
        assertThat(users.findById(pending.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void neitherAdminInterfaceCanReactivateACachedExpiredSignup() throws Exception {
        assertCachedExpiredSignupCannotBeReactivated(false);
        assertCachedExpiredSignupCannotBeReactivated(true);
    }

    private void assertCachedExpiredSignupCannotBeReactivated(boolean drive) throws Exception {
        String id = unique();
        AppUser pending = start(id, id + "@example.test");
        EmailVerificationToken token = openToken(pending.getId());
        token.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(token);
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            AppUser stale = requestEntityManager.find(AppUser.class, pending.getId());
            pool.submit(() -> expiry.expireIfEligible(pending.getId(), LocalDateTime.now())).get(5, TimeUnit.SECONDS);
            assertThat(stale.isExpiredEmailSignup()).isFalse();
            assertThatThrownBy(() -> {
                if (drive) driveAdmins.updateUserStatus(true, pending.getId(), true);
                else admins.updateUserActive(-1L, pending.getId(), true);
            }).isInstanceOf(BadRequestException.class).hasMessageContaining("만료된 가입");
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
        assertThat(users.findById(pending.getId()).orElseThrow().isExpiredEmailSignup()).isTrue();
        assertThat(users.findById(pending.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void expiryReleasesIdentifiersScrubsHistoryAndCannotBeReactivated() {
        String id = unique();
        String email = id + "@example.test";
        AppUser pending = start(id, email);
        audit.record(id, "192.0.2.1", "browser", LoginAuditStatus.BAD_CREDENTIALS, "details", pending);
        var auditId = logs.findAll().stream().filter(log -> id.equals(log.getLoginId())).findFirst().orElseThrow().getId();
        var token = openToken(pending.getId());
        assertThat(token.isCleanupEligible()).isTrue();
        pending.setSignupPinFailures(5);
        pending.setSignupPinWindowStartedAt(LocalDateTime.now());
        pending.setSignupPinBlockedUntil(LocalDateTime.now().plusMinutes(15));
        users.saveAndFlush(pending);
        assertThat(expiry.expireIfEligible(pending.getId(), token.getCreatedAt().plusDays(7).minusNanos(1))).isFalse();
        assertThat(expiry.expireIfEligible(pending.getId(), token.getCreatedAt().plusDays(7))).isTrue();

        AppUser scrubbed = users.findById(pending.getId()).orElseThrow();
        assertThat(scrubbed.isExpiredEmailSignup()).isTrue();
        assertThat(scrubbed.isActive()).isFalse();
        assertThat(scrubbed.getLoginId()).startsWith("expired_").doesNotContain(id);
        assertThat(scrubbed.getEmail()).isNull();
        assertThat(scrubbed.getPasswordHash()).isNull();
        assertThat(scrubbed.getSecondaryPinHash()).isNull();
        assertThat(scrubbed.getSignupPinFailures()).isZero();
        assertThat(scrubbed.getSignupPinWindowStartedAt()).isNull();
        assertThat(scrubbed.getSignupPinBlockedUntil()).isNull();
        assertThat(openTokenOrNull(pending.getId())).isNull();
        var clean = logs.findById(auditId).orElseThrow();
        assertThat(clean.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(clean.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(clean.getUserAgent()).isNull();
        assertThat(clean.getAppUser()).isNull();
        assertThatThrownBy(() -> admins.updateUserActive(-1L, pending.getId(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("만료된 가입");
        assertThatThrownBy(() -> driveAdmins.updateUserStatus(true, pending.getId(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("만료된 가입");
        AppUser newAccount = accounts.registerVerifiedEmailUser(id, "다시 가입", email, "test-password", "12345678");
        assertThat(newAccount.getId()).isNotEqualTo(pending.getId());
    }

    @Test void resendResetsTheSevenDayDeadlineAndExpiredRequestIsReleasedOnResend() {
        String id = unique();
        String email = id + "@example.test";
        AppUser pending = start(id, email);
        var first = openToken(pending.getId());
        first.setCreatedAt(LocalDateTime.now().minusDays(6));
        tokens.saveAndFlush(first);
        signup.resend(new EmailVerificationResendRequest(email));
        var latest = openToken(pending.getId());
        assertThat(latest.getId()).isNotEqualTo(first.getId());
        assertThat(latest.isCleanupEligible()).isTrue();
        assertThat(expiry.expireIfEligible(pending.getId(), LocalDateTime.now())).isFalse();
        latest.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(latest);
        signup.resend(new EmailVerificationResendRequest(email));
        assertThat(users.findById(pending.getId()).orElseThrow().isExpiredEmailSignup()).isTrue();
    }

    @Test void cancelledExistingAndLegacyRegistrationsAreNotExpired() {
        AppUser cancelled = start(unique(), unique() + "@example.test");
        var cancelledToken = openToken(cancelled.getId());
        cancelledToken.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(cancelledToken);
        driveAdmins.updateUserStatus(true, cancelled.getId(), false);
        assertThat(expiry.expireIfEligible(cancelled.getId(), LocalDateTime.now())).isFalse();
        assertThat(users.findById(cancelled.getId()).orElseThrow().getEmail()).isNotNull();

        AppUser legacy = accounts.registerPendingEmailUser(unique(), "기존 신청", unique() + "@example.test", "test-password", "12345678");
        EmailVerificationToken old = new EmailVerificationToken();
        old.setUser(legacy);
        old.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        old.setCreatedAt(LocalDateTime.now().minusDays(8));
        old.setExpiresAt(LocalDateTime.now().minusDays(7));
        tokens.saveAndFlush(old);
        assertThat(expiry.expireIfEligible(legacy.getId(), LocalDateTime.now())).isFalse();
        assertThat(users.findById(legacy.getId()).orElseThrow().getEmail()).isNotNull();

        AppUser active = accounts.registerVerifiedEmailUser(unique(), "기존 회원", unique() + "@example.test", "test-password", "12345678");
        assertThat(expiry.expireIfEligible(active.getId(), LocalDateTime.now())).isFalse();
    }

    @Test void workerFindsOldRequestsAndAnOldLinkCannotReactivateThem() {
        String id = unique();
        String email = id + "@example.test";
        AppUser pending = start(id, email);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), url.capture(), any());
        String raw = url.getValue().split("#verify-email/")[1];
        var old = openToken(pending.getId());
        old.setCreatedAt(LocalDateTime.now().minusDays(8));
        old.setExpiresAt(LocalDateTime.now().plusDays(1));
        tokens.saveAndFlush(old);
        assertThatThrownBy(() -> signup.verify(raw, "12345678")).isInstanceOf(BadRequestException.class);
        worker.expirePendingSignups();
        assertThat(users.findById(pending.getId()).orElseThrow().isExpiredEmailSignup()).isTrue();
        assertThatThrownBy(() -> signup.verify(raw, "12345678")).isInstanceOf(BadRequestException.class);
    }

    @Test void unexpectedAdminOrSocialAccountsAreNeverAutoExpired() {
        AppUser administrator = start(unique(), unique() + "@example.test");
        administrator.setRole(AppUserRole.ADMIN);
        users.saveAndFlush(administrator);
        var adminToken = openToken(administrator.getId());
        adminToken.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(adminToken);
        assertThat(expiry.expireIfEligible(administrator.getId(), LocalDateTime.now())).isFalse();
        assertThat(users.findById(administrator.getId()).orElseThrow().getEmail()).isNotNull();

        AppUser linked = start(unique(), unique() + "@example.test");
        var account = new SocialAccount();
        account.setUser(linked);
        account.setProvider(SocialLoginProvider.KAKAO);
        account.setProviderUserId(UUID.randomUUID().toString());
        socialAccounts.saveAndFlush(account);
        var linkedToken = openToken(linked.getId());
        linkedToken.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(linkedToken);
        assertThat(expiry.expireIfEligible(linked.getId(), LocalDateTime.now())).isFalse();
        assertThat(users.findById(linked.getId()).orElseThrow().getEmail()).isNotNull();
    }

    @Test void concurrentConfirmationAndResendCannotReviveAnExpiredSignup() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        AppUser pending = start(id, email);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), url.capture(), any());
        String raw = url.getValue().split("#verify-email/")[1];
        var old = openToken(pending.getId());
        old.setCreatedAt(LocalDateTime.now().minusDays(8));
        old.setExpiresAt(LocalDateTime.now().plusDays(1));
        tokens.saveAndFlush(old);
        var pool = Executors.newFixedThreadPool(3);
        var expired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var cleanup = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(expiry.expireIfEligible(pending.getId(), LocalDateTime.now())).isTrue();
                expired.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(expired.await(5, TimeUnit.SECONDS)).isTrue();
            var verification = pool.submit(() -> signup.verify(raw, "12345678"));
            var resend = pool.submit(() -> signup.resend(new EmailVerificationResendRequest(email)));
            assertThatThrownBy(() -> verification.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> resend.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            cleanup.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> verification.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            resend.get(5, TimeUnit.SECONDS);
            verify(sender, times(1)).send(eq(email), any(), any());
            assertThat(users.findById(pending.getId()).orElseThrow().isExpiredEmailSignup()).isTrue();
            assertThat(openTokenOrNull(pending.getId())).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void lateAuditWriteWaitsForExpiryAndDoesNotReintroduceIdentity() throws Exception {
        String id = unique();
        AppUser pending = start(id, id + "@example.test");
        var old = openToken(pending.getId());
        old.setCreatedAt(LocalDateTime.now().minusDays(8));
        tokens.saveAndFlush(old);
        var pool = Executors.newFixedThreadPool(2);
        var expired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var cleanup = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(expiry.expireIfEligible(pending.getId(), LocalDateTime.now())).isTrue();
                expired.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(expired.await(5, TimeUnit.SECONDS)).isTrue();
            var late = pool.submit(() -> audit.record(id, "192.0.2.7", "old browser", LoginAuditStatus.BAD_CREDENTIALS, "old details", pending));
            assertThatThrownBy(() -> late.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            cleanup.get(5, TimeUnit.SECONDS);
            late.get(5, TimeUnit.SECONDS);
            assertThat(logs.findAll().stream().noneMatch(log -> id.equals(log.getLoginId()))).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private AppUser start(String id, String email) {
        signup.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        return users.findByLoginId(id).orElseThrow();
    }

    private EmailVerificationToken openToken(Long userId) {
        return new TransactionTemplate(transactions).execute(status ->
                tokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(userId).orElseThrow());
    }

    private EmailVerificationToken openTokenOrNull(Long userId) {
        return new TransactionTemplate(transactions).execute(status ->
                tokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(userId).orElse(null));
    }

    private String unique() { return "expiry" + UUID.randomUUID().toString().replace("-", ""); }
}

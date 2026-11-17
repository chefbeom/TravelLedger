package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.account.withdrawal.WithdrawalEmailChallengeRepository;
import com.playdata.calen.account.withdrawal.WithdrawalEmailChallenge;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "app.auth.kakao.expected-app-id=1234", "app.auth.kakao.admin-app-id=1234",
        "app.auth.email-verification.enabled=true", "app.auth.email-verification.base-url=https://app.example.test",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-recovery-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class KakaoWithdrawalRecoveryServiceTest {
    @Autowired KakaoWithdrawalRecoveryService recovery;
    @Autowired AppUserService appUsers;
    @Autowired AdminService admins;
    @Autowired AccountEmailVerificationService profileEmail;
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository social;
    @Autowired WithdrawalEmailChallengeRepository challenges;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean WithdrawalRecoveryMailSender recoveryMail;
    @MockitoBean EmailVerificationSender verificationMail;
    @MockitoBean KakaoOAuthClient kakao;

    @Test void aCachedRecoveryProofCannotSurviveAChangedPin() throws Exception {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        var proof = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            requestEntityManager.find(AppUser.class, user.getId());
            WithdrawalEmailChallenge stale = requestEntityManager.find(WithdrawalEmailChallenge.class, proof.getId());
            pool.submit(() -> appUsers.updateSecondaryPin(user.getId(), "12345678", "87654321"))
                    .get(5, TimeUnit.SECONDS);
            assertThat(stale.getUsedAt()).isNull();
            assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
        assertThat(requests.findByUserId(user.getId())).isEmpty();
    }

    @BeforeEach void createEmbeddingOutboxSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void verifiedEmailAndFreshPinAreBothRequiredBeforeStartingWithdrawal() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        assertThatThrownBy(() -> recovery.preview(token, "87654321")).isInstanceOf(BadCredentialsException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(user.getId())).isEmpty();
        assertThat(recovery.preview(token, "12345678").withdrawalAvailable()).isFalse();
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        UUID requestId = recovery.start(token, "12345678", Map.of(), Map.of());
        assertThat(requestId).isNotNull();
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
        assertThat(requests.findByUserId(user.getId())).isPresent();
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
    }

    @Test void unknownLocalAndUnverifiedAccountsDoNotReceiveRecoveryEmail() {
        AppUser local = appUsers.registerUser(unique(), "일반 사용자", "test-password", "12345678");
        local.setEmail(unique() + "@example.test"); users.saveAndFlush(local);
        AppUser unverified = kakaoOnly(); unverified.setEmailVerified(false); users.saveAndFlush(unverified);
        recovery.requestEmailProof("unknown@example.test");
        recovery.requestEmailProof(local.getEmail());
        recovery.requestEmailProof(unverified.getEmail());
        verify(recoveryMail, never()).send(anyString(), anyString(), any());
    }

    @Test void expiredEmailProofCannotAuthorizeAWithdrawal() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        var challenge = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        challenge.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        challenges.saveAndFlush(challenge);
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
        assertThat(requests.findByUserId(user.getId())).isEmpty();
    }

    @Test void anEmailChangeAlsoInvalidatesTheProofEvenIfTheRevocationStepWasMissed() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        user = users.findById(user.getId()).orElseThrow();
        user.setEmail(unique() + "@example.test");
        users.saveAndFlush(user);
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
        assertThat(requests.findByUserId(user.getId())).isEmpty();
    }

    @Test void resendCooldownRetainsExistingProofAndDoesNotStoreTheRawToken() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        recovery.requestEmailProof(user.getEmail());
        verify(recoveryMail).send(anyString(), anyString(), any());
        var challenge = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(challenge.getTokenHash()).hasSize(64).doesNotContain(token);
        assertThat(challenge.getExpiresAt()).isBeforeOrEqualTo(challenge.getCreatedAt().plusMinutes(30));
        assertThat(recovery.preview(token, "12345678").withdrawalAvailable()).isFalse();
    }

    @Test void changingSecondaryPinRevokesAnAlreadyIssuedEmailProof() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        appUsers.updateSecondaryPin(user.getId(), "12345678", "23456789");
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getUsedAt()).isNotNull();
        assertThatThrownBy(() -> recovery.preview(token, "23456789")).isInstanceOf(BadRequestException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void addingALocalPasswordRevokesTheKakaoOnlyRecoveryProof() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        appUsers.updatePassword(user.getId(), "12345678", "new-password");
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getUsedAt()).isNotNull();
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
    }

    @Test void failedOwnershipSelectionRollsBackWithdrawalAndLeavesTheProofUsable() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        assertThatThrownBy(() -> recovery.start(token, "12345678", Map.of(999L, 888L), Map.of()))
                .isInstanceOf(BadRequestException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(user.getId())).isEmpty();
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(recovery.preview(token, "12345678").withdrawalAvailable()).isFalse();
    }

    @Test void disablingAndReenablingAccountDoesNotReviveOldEmailProof() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        admins.updateUserActive(-1L, user.getId(), false);
        admins.updateUserActive(-1L, user.getId(), true);
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void verifiedEmailChangeRevokesProofForTheOldAddress() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        profileEmail.start(user.getId(), unique() + "@example.test", "12345678");
        var url = ArgumentCaptor.forClass(String.class);
        verify(verificationMail).send(anyString(), url.capture(), any());
        profileEmail.verify(url.getValue().substring(url.getValue().lastIndexOf('/') + 1));
        assertThatThrownBy(() -> recovery.preview(token, "12345678")).isInstanceOf(BadRequestException.class);
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getUsedAt()).isNotNull();
    }

    @Test void deliveryFailureDoesNotLeaveAUsableProofOrDiscloseTheAccount() {
        AppUser user = kakaoOnly();
        doThrow(new ServiceUnavailableException("mail offline")).when(recoveryMail).send(anyString(), anyString(), any());
        recovery.requestEmailProof(user.getEmail());
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test void failedResendPreservesThePreviousUsableProof() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        var old = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        old.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        challenges.saveAndFlush(old);
        doThrow(new ServiceUnavailableException("mail offline")).when(recoveryMail).send(anyString(), anyString(), any());
        recovery.requestEmailProof(user.getEmail());
        assertThat(challenges.findById(old.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(recovery.preview(token, "12345678").withdrawalAvailable()).isFalse();
    }

    @Test void recoveryPinFailuresPersistAndFollowAReissuedLink() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> recovery.preview(token, "87654321")).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> recovery.preview(token, "87654321")).isInstanceOf(TooManyRequestsException.class);
        var previous = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(previous.getPinFailures()).isEqualTo(5);
        assertThat(previous.getPinBlockedUntil()).isNotNull();
        previous.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        challenges.saveAndFlush(previous);

        recovery.requestEmailProof(user.getEmail());
        var sentUrls = ArgumentCaptor.forClass(String.class);
        verify(recoveryMail, times(2)).send(anyString(), sentUrls.capture(), any());
        String newUrl = sentUrls.getAllValues().get(1);
        String newToken = newUrl.substring(newUrl.lastIndexOf('/') + 1);
        var renewed = challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(renewed.getPinFailures()).isEqualTo(5);
        assertThat(renewed.getPinBlockedUntil()).isEqualTo(previous.getPinBlockedUntil());
        assertThatThrownBy(() -> recovery.preview(newToken, "12345678")).isInstanceOf(TooManyRequestsException.class);
        assertThat(requests.findByUserId(user.getId())).isEmpty();
    }

    @Test void wrongPinAtFinalStartPersistsFailureWithoutDeactivatingAccount() {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        assertThatThrownBy(() -> recovery.start(token, "87654321", Map.of(), Map.of()))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(challenges.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getPinFailures()).isEqualTo(1);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(user.getId())).isEmpty();
    }

    @Test void concurrentUsesOfTheSameProofCannotStartTwice() throws Exception {
        AppUser user = kakaoOnly();
        String token = requestProof(user.getEmail());
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); release.await(); return recovery.start(token, "12345678", Map.of(), Map.of()); });
            var second = pool.submit(() -> { ready.countDown(); release.await(); return recovery.start(token, "12345678", Map.of(), Map.of()); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
            int succeeded = 0, rejected = 0;
            for (var future : List.of(first, second)) {
                try { assertThat(future.get(15, TimeUnit.SECONDS)).isNotNull(); succeeded++; }
                catch (ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(BadRequestException.class); rejected++; }
            }
            assertThat(succeeded).isEqualTo(1); assertThat(rejected).isEqualTo(1);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser kakaoOnly() {
        AppUser user = appUsers.registerKakaoOnlyUser("카카오 사용자", unique() + "@example.test", "12345678");
        var account = new SocialAccount(); account.setUser(user); account.setProvider(SocialLoginProvider.KAKAO);
        account.setProviderUserId(Long.toString(1_000_000_000L + user.getId())); account.setProviderAppId(1234L); social.saveAndFlush(account);
        return user;
    }

    private String requestProof(String email) {
        recovery.requestEmailProof(email);
        var url = ArgumentCaptor.forClass(String.class);
        verify(recoveryMail).send(anyString(), url.capture(), any());
        assertThat(url.getValue()).startsWith("https://app.example.test/#withdraw-kakao/");
        return url.getValue().substring(url.getValue().lastIndexOf('/') + 1);
    }

    private String unique() { return "rec" + UUID.randomUUID().toString().replace("-", ""); }
}

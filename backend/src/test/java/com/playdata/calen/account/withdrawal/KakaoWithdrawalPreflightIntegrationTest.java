package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "app.auth.kakao.expected-app-id=1234", "app.auth.kakao.admin-app-id=1234",
        "spring.datasource.url=jdbc:h2:mem:kakao-preflight-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class KakaoWithdrawalPreflightIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository social;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired WithdrawalCleanupTaskStore store;
    @Autowired KakaoWithdrawalCleanupExecutor executor;
    @Autowired KakaoOAuthProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean KakaoOAuthClient kakao;

    @BeforeEach void configured() {
        properties.setExpectedAppId(1234L); properties.setAdminAppId(1234L);
        when(kakao.isUnlinkConfigured()).thenReturn(true);
    }

    @Test void activeAccountNeverReachesTheProvider() {
        Fixture fixture = fixture(true, "101001", "101001");
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(anyString());
        WithdrawalCleanupTask task = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("KAKAO_ACCOUNT_STATE_INVALID");
        assertThat(task.getEncryptedPayload()).isNotNull();
        assertThat(users.findById(fixture.userId()).orElseThrow().isActive()).isTrue();
    }

    @Test void changedProviderBindingNeverReachesTheProvider() {
        Fixture fixture = fixture(false, "101002", "101003");
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(anyString());
        assertThat(tasks.findById(fixture.taskId().toString()).orElseThrow().getState())
                .isEqualTo(WithdrawalCleanupTask.State.FAILED);
    }

    @Test void legacyAccountWithoutVerifiedAppNeverReachesTheProvider() {
        Fixture fixture = fixture(false, "101005", "101005");
        SocialAccount account = social.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, "101005").orElseThrow();
        account.setProviderAppId(null); social.saveAndFlush(account);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(anyString());
        assertThat(tasks.findById(fixture.taskId().toString()).orElseThrow().getEncryptedPayload()).isNotNull();
    }

    @Test void mismatchedAdminAppAttestationNeverReachesTheProvider() {
        Fixture fixture = fixture(false, "101006", "101006");
        properties.setAdminAppId(999L);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(anyString());
        assertThat(tasks.findById(fixture.taskId().toString()).orElseThrow().getEncryptedPayload()).isNotNull();
    }

    @Test void mismatchedVerifiedAppNeverReachesTheProvider() {
        fixture(false, "101007", "101007");
        SocialAccount account = social.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, "101007").orElseThrow();
        account.setProviderAppId(999L); social.saveAndFlush(account);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(anyString());
    }

    @Test void matchingInactiveAccountIsUnlinkedAndOnlyThenMarkedSuccessful() {
        Fixture fixture = fixture(false, "101004", "101004");
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.SUCCEEDED);
        verify(kakao).unlinkAccount("101004");
        WithdrawalCleanupTask task = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(task.getEncryptedPayload()).isNull();
    }

    private Fixture fixture(boolean active, String linkedSubject, String targetSubject) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser user = new AppUser(); user.setLoginId("preflight-" + UUID.randomUUID());
            user.setDisplayName("테스트 사용자"); user.setActive(active); user = users.saveAndFlush(user);
            SocialAccount account = new SocialAccount(); account.setUser(user); account.setProvider(SocialLoginProvider.KAKAO);
            account.setProviderUserId(linkedSubject); account.setProviderAppId(1234L); social.saveAndFlush(account);
            UUID requestId = UUID.randomUUID(); LocalDateTime now = LocalDateTime.now();
            AccountWithdrawalRequest request = new AccountWithdrawalRequest(); request.setId(requestId.toString());
            request.setUserId(user.getId()); request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
            request.setRequestedAt(now); request.setUpdatedAt(now); requests.saveAndFlush(request);
            UUID taskId = store.enqueue(requestId, WithdrawalCleanupKind.KAKAO_UNLINK, "social:" + account.getId(), targetSubject);
            return new Fixture(user.getId(), taskId);
        });
    }

    private record Fixture(Long userId, UUID taskId) {}
}

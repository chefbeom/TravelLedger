package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.auth.kakao.expected-app-id=1234", "app.auth.kakao.admin-app-id=1234",
        "app.auth.kakao.frontend-base-url=https://app.example.test",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "spring.datasource.url=jdbc:h2:mem:kakao-reconnect-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class KakaoWithdrawalReconnectIntegrationTest {
    private static final String SUBJECT = "123456789";
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository social;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired WithdrawalCleanupTaskStore store;
    @Autowired KakaoWithdrawalReconnectGuard guard;
    @Autowired KakaoWithdrawalCleanupExecutor executor;
    @Autowired KakaoOAuthProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserService accounts;
    @MockitoBean KakaoOAuthClient kakao;

    @BeforeEach void prepare() {
        tasks.deleteAll(); social.deleteAll(); requests.deleteAll();
        properties.setExpectedAppId(1234L); properties.setAdminAppId(1234L);
        when(kakao.isConfigured()).thenReturn(true);
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        when(kakao.authorizationUrl(anyString())).thenAnswer(call -> "https://kauth.example.test/authorize?state=" + call.getArgument(0));
    }

    @Test void successfulUnlinkIsRearmedWhenTheSameVerifiedAppReconnects() {
        Fixture f = fixture(false, true);
        var original = store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.succeed(original.taskId(), original.token())).isTrue();
        assertThat(tasks.findById(f.taskId().toString()).orElseThrow().getEncryptedPayload()).isNull();

        assertThat(guard.requeueIfWithdrawing(identity(1234L))).isTrue();
        var task = tasks.findById(f.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        assertThat(task.getLastErrorCode()).isEqualTo("KAKAO_RECONNECTED");
        assertThat(task.getEncryptedPayload()).isNotNull().doesNotContain(SUBJECT);
        var next = store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)).orElseThrow();
        assertThat(next.payload()).isEqualTo(SUBJECT);
        assertThat(users.findById(f.userId()).orElseThrow().isActive()).isFalse();
    }

    @Test void ambiguousAppInvalidatesPreviousSuccessWithoutAllowingAnAutomaticCall() {
        Fixture f = fixture(false, true);
        var original = store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.succeed(original.taskId(), original.token())).isTrue();
        assertThat(guard.requeueIfWithdrawing(identity(999L))).isTrue();
        var task = tasks.findById(f.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("KAKAO_RECONNECT_UNVERIFIED");
        assertThat(task.getEncryptedPayload()).isNotNull();
        assertThat(store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5))).isEmpty();
        assertThat(store.retryFailed(f.taskId())).isFalse();
    }

    @Test void ordinaryActiveAndAdministrativelyDisabledAccountsAreNotChanged() {
        fixture(true, false);
        assertThat(guard.requeueIfWithdrawing(identity(1234L))).isFalse();
        assertThat(tasks.count()).isZero();
        tasks.deleteAll(); social.deleteAll(); requests.deleteAll();
        fixture(false, false);
        assertThat(guard.requeueIfWithdrawing(identity(1234L))).isFalse();
        assertThat(tasks.count()).isZero();
    }

    @Test void activeAccountWithAnInProgressWithdrawalIsQuarantinedInsteadOfSignedIn() {
        Fixture f = fixture(true, true);
        assertThat(guard.requeueIfWithdrawing(identity(1234L))).isTrue();
        var task = tasks.findById(f.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("KAKAO_RECONNECT_UNVERIFIED");
        assertThat(store.retryFailed(f.taskId())).isFalse();
    }

    @Test void reconnectDuringProviderCallInvalidatesTheOldClaimAndDelaysTheNextOne() throws Exception {
        Fixture f = fixture(false, true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("provider fixture timed out");
            return null;
        }).when(kakao).unlinkAccount(SUBJECT);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var running = pool.submit(executor::processNext);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var oldTask = tasks.findById(f.taskId().toString()).orElseThrow();
            LocalDateTime oldLease = oldTask.getLeaseUntil();
            assertThat(oldTask.getState()).isEqualTo(WithdrawalCleanupTask.State.PROCESSING);
            assertThat(guard.requeueIfWithdrawing(identity(999L))).isTrue();
            var ambiguous = tasks.findById(f.taskId().toString()).orElseThrow();
            assertThat(ambiguous.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
            assertThat(ambiguous.getNextAttemptAt()).isAfter(oldLease);
            assertThat(store.retryFailed(f.taskId())).isFalse();
            assertThat(guard.requeueIfWithdrawing(identity(1234L))).isTrue();
            assertThat(guard.requeueIfWithdrawing(identity(1234L))).isTrue();
            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.LOST_LEASE);
            var pending = tasks.findById(f.taskId().toString()).orElseThrow();
            assertThat(pending.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(pending.getNextAttemptAt()).isAfter(oldLease);
            assertThat(store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5))).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void oauthCallbackCannotSignInAnAccountWhoseUnlinkMustBeRepeated() throws Exception {
        Fixture f = fixture(false, true);
        var first = store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.succeed(first.taskId(), first.token())).isTrue();
        when(kakao.fetchIdentity("fixture-code")).thenReturn(identity(1234L));
        var started = mvc.perform(get("/api/auth/oauth/kakao/start")).andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) started.getRequest().getSession(false);
        String state = (String) session.getAttribute("CALEN_KAKAO_OAUTH_STATE");
        mvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("state", state).param("code", "fixture-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://app.example.test/#signup?kakao=unavailable"));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(tasks.findById(f.taskId().toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
    }

    @Test void oauthLinkCallbackCannotAttachAWithdrawingUsersReconnectedKakaoAccount() throws Exception {
        Fixture f = fixture(false, true);
        var first = store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.succeed(first.taskId(), first.token())).isTrue();
        AppUser actor = accounts.registerUser("actor-" + UUID.randomUUID(), "일반 사용자", "test-password", "12345678");
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("loginId", actor.getLoginId(), "password", "test-password",
                        "secondaryPin", "12345678", "rememberDevice", false)))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/oauth/kakao/link/start").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("secondaryPin", "12345678")))).andExpect(status().isNoContent());
        String state = (String) session.getAttribute("CALEN_KAKAO_OAUTH_STATE");
        when(kakao.fetchIdentity("fixture-code")).thenReturn(identity(1234L));
        mvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("state", state).param("code", "fixture-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://app.example.test/#profile?kakao-link=unavailable"));
        assertThat(social.existsByUserIdAndProvider(actor.getId(), SocialLoginProvider.KAKAO)).isFalse();
        assertThat(tasks.findById(f.taskId().toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    private KakaoIdentity identity(Long appId) { return new KakaoIdentity(SUBJECT, "fixture@example.test", "테스트", true, appId); }

    private Fixture fixture(boolean active, boolean withdrawal) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser user = new AppUser(); user.setLoginId("reconnect-" + UUID.randomUUID());
            user.setDisplayName("테스트"); user.setActive(active); user = users.saveAndFlush(user);
            SocialAccount account = new SocialAccount(); account.setUser(user); account.setProvider(SocialLoginProvider.KAKAO);
            account.setProviderUserId(SUBJECT); account.setProviderAppId(1234L); social.saveAndFlush(account);
            if (!withdrawal) return new Fixture(user.getId(), null);
            UUID requestId = UUID.randomUUID(); LocalDateTime now = LocalDateTime.now();
            AccountWithdrawalRequest request = new AccountWithdrawalRequest(); request.setId(requestId.toString());
            request.setUserId(user.getId()); request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
            request.setRequestedAt(now); request.setUpdatedAt(now); requests.saveAndFlush(request);
            UUID taskId = store.enqueue(requestId, WithdrawalCleanupKind.KAKAO_UNLINK, "social:" + account.getId(), SUBJECT);
            return new Fixture(user.getId(), taskId);
        });
    }

    private record Fixture(Long userId, UUID taskId) {}
}

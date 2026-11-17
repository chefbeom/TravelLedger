package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import jakarta.servlet.http.Cookie;
import jakarta.persistence.EntityManagerFactory;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:remember-binding-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class RememberMeAccountBindingIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository socialAccounts;
    @Autowired AuthenticationCommitFence fence;
    @Autowired AccountRememberMeTokens tokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void anOldCookieAndSessionCannotAuthenticateOrRevokeAReusedLoginName() throws Exception {
        String loginId = "reuse-" + UUID.randomUUID().toString().substring(0, 8);
        AppUser original = user(loginId);
        var first = login(loginId);
        Cookie oldCookie = first.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(oldCookie).isNotNull();
        assertThat(jdbc.queryForObject("select user_id from persistent_logins where username=?", Long.class, loginId)).isEqualTo(original.getId());
        MockHttpSession oldSession = (MockHttpSession) first.getRequest().getSession(false);

        original.setActive(false);
        original.setLoginId("retired-" + original.getId());
        original.setEmail(null);
        users.saveAndFlush(original);
        AppUser replacement = user(loginId);
        Cookie newCookie = login(loginId).getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(newCookie).isNotNull();

        mvc.perform(get("/api/auth/me").cookie(oldCookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where user_id=?", Integer.class, replacement.getId())).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(newCookie)).andExpect(status().isOk());
    }

    @Test void loginWaitingForDeactivationCannotCreateSessionOrToken() throws Exception {
        AppUser user = user("fence-" + UUID.randomUUID().toString().substring(0, 8));
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        try {
            var disable = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(user.getId()).orElseThrow();
                current.setActive(false);
                users.saveAndFlush(current);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var login = pool.submit(() -> fence.withLocal(principal, user.getSecondaryPinHash(), current -> {
                completed.set(true);
                return null;
            }));
            assertThatThrownBy(() -> login.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadCredentialsException.class);
            assertThat(completed).isFalse();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void tokenCreatedBeforeDeactivationIsRevokedAfterTheAccountLockIsReleased() throws Exception {
        AppUser user = user("fence-" + UUID.randomUUID().toString().substring(0, 8));
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        String series = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(2);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var login = pool.submit(() -> fence.withLocal(principal, user.getSecondaryPinHash(), current -> {
                tokens.createNewToken(new PersistentRememberMeToken(current.getLoginId(), series, "value", new Date()));
                created.countDown();
                await(release);
                return null;
            }));
            assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
            var disable = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(user.getId()).orElseThrow();
                current.setActive(false);
                tokens.removeUserTokensById(current.getId());
            }));
            assertThatThrownBy(() -> disable.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            login.get(5, TimeUnit.SECONDS);
            disable.get(5, TimeUnit.SECONDS);
            assertThat(tokens.getTokenForSeries(series)).isNull();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void anUnboundLegacyTokenCannotAuthenticateANewOwnerOfTheSameName() {
        String login = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        String series = UUID.randomUUID().toString();
        jdbc.update("insert into persistent_logins(username,series,token,last_used,user_id) values (?,?,?,current_timestamp,null)",
                login, series, "old-token");
        user(login);
        assertThat(tokens.getTokenForSeries(series)).isNull();
        assertThat(jdbc.queryForObject("select user_id from persistent_logins where series=?", Long.class, series)).isNull();
    }

    @Test void changedPinOrUnlinkedKakaoIsRecheckedBeforeSignIn() {
        AppUser user = user("fence-" + UUID.randomUUID().toString().substring(0, 8));
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        String previousPinHash = user.getSecondaryPinHash();
        user.setSecondaryPinHash(passwords.encode("87654321"));
        users.saveAndFlush(user);
        assertThatThrownBy(() -> fence.withLocal(principal, previousPinHash, current -> null))
                .isInstanceOf(BadCredentialsException.class);

        SocialAccount social = new SocialAccount();
        social.setUser(user);
        social.setProvider(SocialLoginProvider.KAKAO);
        social.setProviderUserId(UUID.randomUUID().toString());
        socialAccounts.saveAndFlush(social);
        var identity = new com.playdata.calen.account.social.KakaoIdentity(social.getProviderUserId(), "user@example.test", "user", true);
        assertThat(fence.withKakao(user.getId(), identity, AppUser::getId)).isEqualTo(user.getId());
        socialAccounts.delete(social);
        socialAccounts.flush();
        assertThatThrownBy(() -> fence.withKakao(user.getId(), identity, current -> null))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test void aPreviouslyLoadedRequestScopedUserIsRefreshedAfterDeactivation() throws Exception {
        AppUser user = user("stale-" + UUID.randomUUID().toString().substring(0, 8));
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            AppUser stale = users.findById(user.getId()).orElseThrow();
            assertThat(requestEntityManager.contains(stale)).isTrue();
            pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(user.getId()).orElseThrow();
                current.setActive(false);
            })).get(5, TimeUnit.SECONDS);
            assertThat(stale.isActive()).isTrue();
            assertThatThrownBy(() -> fence.withLocal(principal, user.getSecondaryPinHash(), current -> null))
                    .isInstanceOf(BadCredentialsException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
    }

    private AppUser user(String loginId) {
        AppUser user = new AppUser();
        user.setLoginId(loginId);
        user.setDisplayName(loginId);
        user.setEmail(loginId + "@example.test");
        user.setPasswordHash(passwords.encode("test1234"));
        user.setSecondaryPinHash(passwords.encode("12345678"));
        return users.saveAndFlush(user);
    }

    private org.springframework.test.web.servlet.MvcResult login(String loginId) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", loginId, "password", "test1234",
                                "secondaryPin", "12345678", "rememberDevice", true))))
                .andExpect(status().isOk()).andReturn();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
}

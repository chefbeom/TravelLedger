package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.EmailVerificationToken;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.EmailVerificationService;
import com.playdata.calen.account.service.EmailVerificationSender;
import com.playdata.calen.account.service.SecondaryPinAttemptService;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:profile-email-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.auth.email-verification.base-url=https://app.example.test"
})
@AutoConfigureMockMvc
class AccountEmailVerificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserService accounts;
    @Autowired AppUserRepository users;
    @Autowired AccountEmailVerificationRepository tokens;
    @Autowired EmailVerificationTokenRepository signupTokens;
    @Autowired EmailVerificationService signupVerification;
    @Autowired AdminService admins;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SecondaryPinAttemptService pinAttempts;
    @MockitoBean EmailVerificationSender sender;

    @Test void authenticatedUserCanVerifyAnEmailWithoutReplacingTheOldOneEarly() throws Exception {
        String id = unique();
        AppUser user = accounts.registerVerifiedEmailUser(id, "사용자", id + "@example.test", "test-password", "12345678");
        MockHttpSession session = login(id);
        String target = unique() + "@example.test";
        request(session, target, "12345678").andExpect(status().isOk()).andExpect(jsonPath("$.verificationRequired").value(true));
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(id + "@example.test");
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(target), url.capture(), any());
        String raw = url.getValue().split("#verify-profile-email/")[1];
        assertThat(tokens.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getTokenHash()).doesNotContain(raw);
        confirm(raw).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        AppUser verified = users.findById(user.getId()).orElseThrow();
        assertThat(verified.getEmail()).isEqualTo(target);
        assertThat(verified.isEmailVerified()).isTrue();
        confirm(raw).andExpect(status().isBadRequest());
    }

    @Test void emailRequestRequiresAuthenticationCsrfAndCorrectPin() throws Exception {
        mvc.perform(post("/api/auth/profile/email-verification").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", "test@example.test", "secondaryPin", "12345678"))))
                .andExpect(status().isUnauthorized());
        String id = unique();
        accounts.registerUser(id, "사용자", "test-password", "12345678");
        MockHttpSession session = login(id);
        mvc.perform(post("/api/auth/profile/email-verification").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", "test@example.test", "secondaryPin", "12345678"))))
                .andExpect(status().isForbidden());
        request(session, "test@example.test", "87654321").andExpect(status().isUnauthorized());
        verify(sender, never()).send(any(), any(), any());
    }

    @Test void expiredEmailTokenDoesNotModifyTheAccount() throws Exception {
        String id = unique();
        AppUser user = accounts.registerUser(id, "사용자", "test-password", "12345678");
        String target = unique() + "@example.test";
        request(login(id), target, "12345678").andExpect(status().isOk());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(target), url.capture(), any());
        String raw = url.getValue().split("#verify-profile-email/")[1];
        var token = tokens.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        token.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        tokens.saveAndFlush(token);
        confirm(raw).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isNull();
    }

    @Test void emailClaimedByAnotherAccountAfterMailWasSentIsNotTransferred() throws Exception {
        String id = unique();
        AppUser user = accounts.registerUser(id, "사용자", "test-password", "12345678");
        String target = unique() + "@example.test";
        request(login(id), target, "12345678").andExpect(status().isOk());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(target), url.capture(), any());
        accounts.registerVerifiedEmailUser(unique(), "다른 사용자", target, "test-password", "12345678");
        confirm(url.getValue().split("#verify-profile-email/")[1]).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isNull();
    }

    @Test void resendCooldownDoesNotInvalidateThePreviousLink() throws Exception {
        String id = unique();
        AppUser user = accounts.registerUser(id, "사용자", "test-password", "12345678");
        MockHttpSession session = login(id);
        String target = unique() + "@example.test";
        request(session, target, "12345678").andExpect(status().isOk());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(target), url.capture(), any());
        request(session, unique() + "@example.test", "12345678").andExpect(status().isTooManyRequests());
        confirm(url.getValue().split("#verify-profile-email/")[1]).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(target);
    }

    @Test void signupVerificationCannotReactivateADisabledPreviouslyVerifiedAccount() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        AppUser user = accounts.registerVerifiedEmailUser(id, "사용자", email, "test-password", "12345678");
        user.setActive(false);
        users.saveAndFlush(user);
        mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email)))).andExpect(status().isOk());
        verify(sender, never()).send(any(), any(), any());

        String raw = UUID.randomUUID().toString();
        EmailVerificationToken old = new EmailVerificationToken();
        old.setUser(user);
        old.setTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))));
        old.setCreatedAt(LocalDateTime.now());
        old.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        signupTokens.saveAndFlush(old);
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", raw, "secondaryPin", "12345678")))).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void signupResendCooldownDoesNotRevealWhetherAnEmailHasAPendingAccount() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        String pendingResponse = mvc.perform(post("/api/auth/email-verification/resend").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("email", email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknownResponse = mvc.perform(post("/api/auth/email-verification/resend").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("email", unique() + "@example.test"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(pendingResponse).isEqualTo(unknownResponse);
        verify(sender, times(1)).send(eq(email), any(), any());
        Boolean tokenStillOpen = new TransactionTemplate(transactions).execute(status -> signupTokens
                .findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(users.findByLoginId(id).orElseThrow().getId())
                .isPresent());
        assertThat(tokenStillOpen).isTrue();
    }

    @Test void signupPinIsRequiredAndRepeatedWrongPinsAreRateLimitedWithoutConsumingTheToken() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), url.capture(), any());
        String token = url.getValue().split("#verify-email/")[1];
        for (int attempt = 1; attempt <= 5; attempt++) {
            var result = mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("token", token, "secondaryPin", "00000000"))));
            if (attempt < 5) result.andExpect(status().isBadRequest());
            else result.andExpect(status().isTooManyRequests());
        }
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", token, "secondaryPin", "12345678"))))
                .andExpect(status().isTooManyRequests());
        AppUser pending = users.findByLoginId(id).orElseThrow();
        assertThat(pending.isActive()).isFalse();
        assertThat(pending.isEmailVerified()).isFalse();
        Boolean tokenStillOpen = new TransactionTemplate(transactions).execute(status ->
                signupTokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(pending.getId()).isPresent());
        assertThat(tokenStillOpen).isTrue();
    }

    @Test void signupPinLimitSurvivesNodeLocalResetAndAReissuedLink() throws Exception {
        String id = unique(), email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        AppUser pending = users.findByLoginId(id).orElseThrow();
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), links.capture(), any());
        String firstToken = links.getValue().split("#verify-email/")[1];
        for (int attempt = 1; attempt <= 2; attempt++) {
            mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("token", firstToken, "secondaryPin", "00000000"))))
                    .andExpect(status().isBadRequest());
            // Simulate a new node or a process restart without shared Redis state.
            pinAttempts.recordSuccess(pending.getId());
        }
        assertThat(users.findById(pending.getId()).orElseThrow().getSignupPinFailures()).isEqualTo(2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var current = signupTokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(pending.getId()).orElseThrow();
            current.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        });
        mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email)))).andExpect(status().isOk());
        verify(sender, times(2)).send(eq(email), links.capture(), any());
        String nextToken = links.getValue().split("#verify-email/")[1];
        for (int attempt = 3; attempt <= 5; attempt++) {
            var result = mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("token", nextToken, "secondaryPin", "00000000"))));
            if (attempt < 5) result.andExpect(status().isBadRequest());
            else result.andExpect(status().isTooManyRequests());
            pinAttempts.recordSuccess(pending.getId());
        }
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", nextToken, "secondaryPin", "12345678"))))
                .andExpect(status().isTooManyRequests());
        AppUser locked = users.findById(pending.getId()).orElseThrow();
        assertThat(locked.isEmailVerified()).isFalse();
        assertThat(locked.getSignupPinFailures()).isEqualTo(5);
        assertThat(locked.getSignupPinBlockedUntil()).isNotNull();
        locked.setSignupPinBlockedUntil(LocalDateTime.now().minusSeconds(1));
        locked.setSignupPinWindowStartedAt(LocalDateTime.now().minusMinutes(16));
        users.saveAndFlush(locked);
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", nextToken, "secondaryPin", "12345678"))))
                .andExpect(status().isOk());
        AppUser verified = users.findById(pending.getId()).orElseThrow();
        assertThat(verified.isEmailVerified()).isTrue();
        assertThat(verified.getSignupPinFailures()).isZero();
        assertThat(verified.getSignupPinBlockedUntil()).isNull();
        assertThat(verified.getSignupPinWindowStartedAt()).isNull();
    }

    @Test void failedSignupResendIsGenericAndKeepsThePreviousLinkUsable() throws Exception {
        String id = unique(), email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        AppUser pending = users.findByLoginId(id).orElseThrow();
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), link.capture(), any());
        String oldToken = link.getValue().split("#verify-email/")[1];
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var current = signupTokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(pending.getId()).orElseThrow();
            current.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        });
        doThrow(new ServiceUnavailableException("private SMTP failure")).when(sender).send(eq(email), any(), any());
        String failed = mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", unique() + "@example.test"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(failed).isEqualTo(unknown).doesNotContain("private", email);
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", oldToken, "secondaryPin", "12345678"))))
                .andExpect(status().isOk());
        assertThat(users.findById(pending.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test void concurrentSignupPinAttemptsAreSerializedByTheAccountRow() throws Exception {
        String id = unique(), email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        AppUser pending = users.findByLoginId(id).orElseThrow();
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), link.capture(), any());
        String token = link.getValue().split("#verify-email/")[1];
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var release = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 6).mapToObj(index -> pool.submit(() -> {
                ready.countDown(); release.await();
                try { signupVerification.verify(token, "00000000"); return (RuntimeException) null; }
                catch (RuntimeException failure) { return failure; }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            int wrong = 0, limited = 0;
            for (var future : futures) {
                RuntimeException result = future.get(15, TimeUnit.SECONDS);
                if (result instanceof TooManyRequestsException) limited++;
                else { assertThat(result).isInstanceOf(BadRequestException.class); wrong++; }
            }
            assertThat(wrong).isEqualTo(4);
            assertThat(limited).isEqualTo(2);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
        AppUser locked = users.findById(pending.getId()).orElseThrow();
        assertThat(locked.getSignupPinFailures()).isEqualTo(5);
        assertThat(locked.getSignupPinBlockedUntil()).isNotNull();
        assertThat(locked.isEmailVerified()).isFalse();
    }

    @Test void adminCancellationPreventsPendingSignupFromRestartingUntilExplicitlyReenabled() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        AppUser user = users.findByLoginId(id).orElseThrow();
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), links.capture(), any());
        String oldToken = links.getValue().split("#verify-email/")[1];

        admins.updateUserActive(-1L, user.getId(), false);
        mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email)))).andExpect(status().isOk());
        verify(sender, times(1)).send(eq(email), any(), any());
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", oldToken, "secondaryPin", "12345678")))).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();

        admins.updateUserActive(-1L, user.getId(), true);
        mvc.perform(post("/api/auth/email-verification/resend").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email)))).andExpect(status().isOk());
        verify(sender, times(2)).send(eq(email), links.capture(), any());
        String newToken = links.getValue().split("#verify-email/")[1];
        mvc.perform(post("/api/auth/email-verification/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", newToken, "secondaryPin", "12345678")))).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test void adminDeactivationInvalidatesPreviouslyIssuedProfileEmailLinks() throws Exception {
        String id = unique();
        String original = id + "@example.test";
        AppUser user = accounts.registerVerifiedEmailUser(id, "사용자", original, "test-password", "12345678");
        String target = unique() + "@example.test";
        request(login(id), target, "12345678").andExpect(status().isOk());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(target), url.capture(), any());
        String raw = url.getValue().split("#verify-profile-email/")[1];
        admins.updateUserActive(-1L, user.getId(), false);
        admins.updateUserActive(-1L, user.getId(), true);
        confirm(raw).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(original);
    }

    @Test void concurrentVerificationAndResendCannotUndoAdminCancellation() throws Exception {
        String id = unique();
        String email = id + "@example.test";
        signupVerification.start(new AuthRegisterRequest(id, "사용자", email, "test-password", "12345678", false));
        AppUser user = users.findByLoginId(id).orElseThrow();
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), url.capture(), any());
        String raw = url.getValue().split("#verify-email/")[1];
        var pool = Executors.newFixedThreadPool(3);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var admin = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                admins.updateUserActive(-1L, user.getId(), false);
                cancelled.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("admin transaction timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            var verifyRequest = pool.submit(() -> signupVerification.verify(raw, "12345678"));
            var resendRequest = pool.submit(() -> signupVerification.resend(new EmailVerificationResendRequest(email)));
            assertThatThrownBy(() -> verifyRequest.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> resendRequest.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            admin.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> verifyRequest.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.playdata.calen.common.exception.BadRequestException.class);
            resendRequest.get(5, TimeUnit.SECONDS);
            verify(sender, times(1)).send(eq(email), any(), any());
            assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private String unique() { return "emailtest" + UUID.randomUUID().toString().replace("-", ""); }
    private MockHttpSession login(String id) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("loginId", id, "password", "test-password", "secondaryPin", "12345678"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private org.springframework.test.web.servlet.ResultActions request(MockHttpSession session, String email, String pin) throws Exception {
        return mvc.perform(post("/api/auth/profile/email-verification").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email, "secondaryPin", pin))));
    }
    private org.springframework.test.web.servlet.ResultActions confirm(String raw) throws Exception {
        return mvc.perform(post("/api/auth/email-verification/verify-profile").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("token", raw))));
    }
}

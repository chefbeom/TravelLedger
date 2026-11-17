package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.security.AuthenticationCommitFence;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.RegistrationPolicyService;
import com.playdata.calen.account.service.SocialAccountService;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.account.social.KakaoPendingRegistration;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:kakao-registration-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.auth.kakao.enabled=true", "app.auth.kakao.client-id=mock-client",
        "app.auth.kakao.redirect-uri=https://app.test/api/auth/oauth/kakao/callback",
        "app.auth.kakao.frontend-base-url=https://app.test"
})
@AutoConfigureMockMvc
class KakaoRegistrationIntegrationTest {
    private static final String PENDING = "CALEN_KAKAO_PENDING_REGISTRATION";
    private static final String STATE_CREATED = "CALEN_KAKAO_OAUTH_STATE_CREATED";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository socialAccounts;
    @Autowired SocialAccountService social;
    @Autowired AppUserService accounts;
    @Autowired RegistrationPolicyService policy;
    @Autowired KakaoOAuthProperties properties;
    @Autowired AuthenticationCommitFence fence;
    @MockitoBean KakaoOAuthClient kakao;

    @BeforeEach void prepare() {
        policy.updatePublicRegistration(true);
        properties.setEnabled(true);
        properties.setExpectedAppId(null);
        when(kakao.isConfigured()).thenAnswer(invocation -> properties.isConfigured());
        when(kakao.authorizationUrl(anyString())).thenAnswer(invocation -> "https://kauth.test/authorize?state=" + invocation.getArgument(0));
    }

    @Test void configuredProviderIsAdvertisedAndStartingAgainReplacesStateAndClearsOldPendingIdentity() throws Exception {
        mvc.perform(get("/api/auth/registration-options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.socialLoginProviders[0]").value("KAKAO"));
        Flow first = start(new MockHttpSession());
        first.session().setAttribute(PENDING, pending(identity(), Instant.now().plusSeconds(600)));
        Flow next = start(first.session());
        assertThat(next.state()).isNotEqualTo(first.state());
        assertThat(next.session().getAttribute(PENDING)).isNull();
        callback(next, "unused", first.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=invalid-state"));
        verify(kakao, never()).fetchIdentity(anyString());
    }

    @Test void anonymousSessionCheckDoesNotDiscardOAuthStateBeforeCallback() throws Exception {
        KakaoIdentity identity = identity();
        Flow flow = start(new MockHttpSession());
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isUnauthorized());
        when(kakao.fetchIdentity("verified-code")).thenReturn(identity);
        callback(flow, "verified-code", flow.state()).andExpect(header().string("Location", "https://app.test/#oauth/kakao/complete"));
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(flow.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(identity.email()));
    }

    @Test void anonymousSessionCheckDoesNotDiscardPendingKakaoSignup() throws Exception {
        KakaoIdentity identity = identity();
        Flow flow = identify(identity);
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(flow.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(identity.email()));
        complete(flow.session(), unique("after-me")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isOk());
    }

    @Test void kakaoOnlySignupNeedsNoLocalCredentialsAndCanUsePrivacyControls() throws Exception {
        KakaoIdentity identity = identity();
        Flow flow = identify(identity);
        MvcResult result = mvc.perform(post("/api/auth/oauth/kakao/complete").session(flow.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of(
                                "displayName", "카카오 사용자", "secondaryPin", "12345678", "rememberDevice", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localLoginEnabled").value(false)).andReturn();
        String internalId = mapper.readTree(result.getResponse().getContentAsByteArray()).get("loginId").asText();
        AppUser created = users.findByLoginId(internalId).orElseThrow();
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getEmail()).isEqualTo(identity.email());
        assertThat(created.isEmailVerified()).isTrue();

        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("loginId", internalId, "password", "any-password",
                                "secondaryPin", "12345678", "rememberDevice", false))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/profile/verify-privacy-access").session(flow.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isNoContent());
        Cookie rememberCookie = result.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(rememberCookie).isNotNull();
        mvc.perform(get("/api/auth/me").cookie(rememberCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.localLoginEnabled").value(false));
    }

    @Test void partialLegacyCredentialsAreRejectedWithoutLosingPendingSignup() throws Exception {
        Flow flow = identify(identity());
        mvc.perform(post("/api/auth/oauth/kakao/complete").session(flow.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of(
                                "loginId", "only-id", "displayName", "사용자", "secondaryPin", "12345678"))))
                .andExpect(status().isBadRequest());
        assertThat(flow.session().getAttribute(PENDING)).isNotNull();
    }

    @Test void existingAccountCanLinkKakaoWithoutLosingItsLocalLogin() throws Exception {
        properties.setExpectedAppId(1234L);
        String loginId = unique("existing");
        AppUser user = accounts.registerUser(loginId, "기존 사용자", "test-password", "12345678");
        MockHttpSession session = loginLocal(loginId);
        mvc.perform(get("/api/auth/oauth/kakao/link").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));
        startLink(session, "12345678").andExpect(status().isNoContent());
        MvcResult authorization = mvc.perform(get("/api/auth/oauth/kakao/link/authorize").session(session))
                .andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        KakaoIdentity original = identity();
        KakaoIdentity identity = new KakaoIdentity(original.providerUserId(), original.email(), original.displayName(), true, 1234L);
        when(kakao.fetchIdentity("link-code")).thenReturn(identity);
        mvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("code", "link-code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://app.test/#profile?kakao-link=connected"));
        mvc.perform(get("/api/auth/oauth/kakao/link").session(session)).andExpect(jsonPath("$.linked").value(true));
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())
                .orElseThrow().getUser().getId()).isEqualTo(user.getId());
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())
                .orElseThrow().getProviderAppId()).isEqualTo(1234L);
        mvc.perform(get("/api/auth/me").session(loginLocal(loginId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId())).andExpect(jsonPath("$.localLoginEnabled").value(true));
        Flow kakaoLogin = identifyLinked(identity);
        mvc.perform(get("/api/auth/me").session(kakaoLogin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
    }

    @Test void changingSecurityInformationInvalidatesAPendingKakaoLink() throws Exception {
        for (int change = 0; change < 4; change++) {
            String loginId = unique("link-version");
            AppUser user = accounts.registerUser(loginId, "기존 사용자", "test-password", "12345678");
            MockHttpSession session = loginLocal(loginId);
            startLink(session, "12345678").andExpect(status().isNoContent());
            Object sealed = session.getAttribute("CALEN_KAKAO_LINK_SECURITY_PROOF");
            assertThat(sealed).isInstanceOf(String.class);
            assertThat((String) sealed).doesNotContain("12345678").doesNotContain(user.getSecondaryPinHash());
            String state = UriComponentsBuilder.fromUriString(mvc.perform(get("/api/auth/oauth/kakao/link/authorize").session(session))
                            .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl())
                    .build().getQueryParams().getFirst("state");
            switch (change) {
                case 0 -> accounts.updateSecondaryPin(user.getId(), "12345678", "87654321");
                case 1 -> accounts.updatePassword(user.getId(), "12345678", "new-password");
                case 2 -> { user.setEmail(unique("new-email") + "@example.test"); users.saveAndFlush(user); }
                case 3 -> accounts.updateSecondaryPin(user.getId(), "12345678", "12345678");
                default -> throw new IllegalStateException();
            }
            KakaoIdentity identity = identity();
            String code = "changed-security-" + change;
            when(kakao.fetchIdentity(code)).thenReturn(identity);
            mvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("code", code).param("state", state))
                    .andExpect(status().isFound()).andExpect(header().string("Location", "https://app.test/#profile?kakao-link=invalid-state"));
            assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())).isEmpty();
            assertThat(session.getAttribute("CALEN_KAKAO_LINK_SECURITY_PROOF")).isNull();
        }
    }

    @Test void kakaoLinkProofCannotBeReusedForAnotherAccount() throws Exception {
        String firstId = unique("link-first"), secondId = unique("link-second");
        accounts.registerUser(firstId, "첫 사용자", "test-password", "12345678");
        accounts.registerUser(secondId, "둘째 사용자", "test-password", "12345678");
        MockHttpSession first = loginLocal(firstId), second = loginLocal(secondId);
        startLink(first, "12345678").andExpect(status().isNoContent());
        startLink(second, "12345678").andExpect(status().isNoContent());
        first.setAttribute("CALEN_KAKAO_LINK_SECURITY_PROOF", second.getAttribute("CALEN_KAKAO_LINK_SECURITY_PROOF"));
        mvc.perform(get("/api/auth/oauth/kakao/link/authorize").session(first)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.test/#profile?kakao-link=invalid-state"));
    }

    @Test void linkingRequiresAuthenticatedSessionCsrfAndCorrectSecondaryPin() throws Exception {
        mvc.perform(post("/api/auth/oauth/kakao/link/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isUnauthorized());
        String loginId = unique("link-guard");
        accounts.registerUser(loginId, "기존 사용자", "test-password", "12345678");
        MockHttpSession session = loginLocal(loginId);
        mvc.perform(post("/api/auth/oauth/kakao/link/start").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isForbidden());
        startLink(session, "87654321").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/oauth/kakao/link/authorize").session(session)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.test/#profile?kakao-link=invalid-state"));
    }

    @Test void kakaoIdentityAlreadyLinkedToAnotherUserCannotBeMergedOrStolen() throws Exception {
        KakaoIdentity identity = identity();
        AppUser owner = social.registerKakaoUser(identity, unique("owner"), "소유자", "test-password", "12345678");
        String loginId = unique("other");
        AppUser other = accounts.registerUser(loginId, "다른 사용자", "test-password", "12345678");
        MockHttpSession session = loginLocal(loginId);
        startLink(session, "12345678").andExpect(status().isNoContent());
        MvcResult authorization = mvc.perform(get("/api/auth/oauth/kakao/link/authorize").session(session))
                .andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        when(kakao.fetchIdentity("taken-code")).thenReturn(identity);
        mvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("code", "taken-code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://app.test/#profile?kakao-link=conflict"));
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())
                .orElseThrow().getUser().getId()).isEqualTo(owner.getId());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.id").value(other.getId()));
    }

    @Test void verifiedSignupCreatesOneNormalAccountWithDefaultsAndUsesOnlyServerIdentity() throws Exception {
        KakaoIdentity identity = identity();
        Flow flow = identify(identity);
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(flow.session()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value(identity.email()))
                .andExpect(jsonPath("$.displayName").value(identity.displayName()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.providerUserId").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        String loginId = unique("kakao");
        var body = mapper.valueToTree(payload(loginId));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("email", "forged@invalid.test").put("role", "ADMIN").put("providerUserId", "forged");
        String oldSession = flow.session().getId();
        mvc.perform(post("/api/auth/oauth/kakao/complete").session(flow.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value(loginId)).andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.localLoginEnabled").value(true));
        AppUser user = users.findByLoginId(loginId).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(identity.email());
        assertThat(user.getRole()).isEqualTo(AppUserRole.USER);
        assertThat(user.isActive() && user.isEmailVerified()).isTrue();
        assertThat(flow.session().getId()).isNotEqualTo(oldSession);
        assertThat(flow.session().getAttribute(PENDING)).isNull();
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId()).orElseThrow().getUser().getId()).isEqualTo(user.getId());
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        mvc.perform(get("/api/categories").session(flow.session())).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists());
        complete(flow.session(), loginId).andExpect(status().isBadRequest());
    }

    @Test void alreadyLinkedUserCanSignInEvenWhenNewRegistrationIsClosed() throws Exception {
        KakaoIdentity identity = identity();
        AppUser existing = social.registerKakaoUser(identity, unique("linked"), "기존 사용자", "test-password", "12345678");
        long before = users.count();
        policy.updatePublicRegistration(false);
        Flow flow = identifyLinked(identity);
        assertThat(users.count()).isEqualTo(before);
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(existing.getId()));
        assertThat(flow.session().getAttribute(PENDING)).isNull();
    }

    @Test void newKakaoOnlySignupPersistsTheVerifiedAppFromTheServerSession() throws Exception {
        properties.setExpectedAppId(1234L);
        KakaoIdentity original = identity();
        KakaoIdentity verified = new KakaoIdentity(original.providerUserId(), original.email(), original.displayName(), true, 1234L);
        Flow flow = identify(verified);
        mvc.perform(post("/api/auth/oauth/kakao/complete").session(flow.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of(
                                "displayName", "카카오 사용자", "secondaryPin", "12345678", "providerAppId", 999))))
                .andExpect(status().isOk());
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, verified.providerUserId())
                .orElseThrow().getProviderAppId()).isEqualTo(1234L);
    }

    @Test void verifiedLoginBackfillsLegacyOriginAndRejectsWrongOrMissingApp() throws Exception {
        KakaoIdentity legacy = identity();
        AppUser owner = social.registerKakaoUser(legacy, unique("legacy-app"), "기존 사용자", "test-password", "12345678");
        properties.setExpectedAppId(1234L);
        KakaoIdentity verified = new KakaoIdentity(legacy.providerUserId(), legacy.email(), legacy.displayName(), true, 1234L);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> fence.withKakao(owner.getId(), verified, current -> {
            throw new IllegalStateException("simulate sign-in failure");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, legacy.providerUserId())
                .orElseThrow().getProviderAppId()).isNull();
        Flow successful = identifyLinked(verified);
        mvc.perform(get("/api/auth/me").session(successful.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.getId()));
        assertThat(socialAccounts.findWithUserByProviderAndProviderUserId(SocialLoginProvider.KAKAO, legacy.providerUserId())
                .orElseThrow().getProviderAppId()).isEqualTo(1234L);

        KakaoIdentity wrong = new KakaoIdentity(legacy.providerUserId(), legacy.email(), legacy.displayName(), true, 999L);
        Flow mismatch = start(new MockHttpSession());
        when(kakao.fetchIdentity(mismatch.state())).thenReturn(wrong);
        callback(mismatch, mismatch.state(), mismatch.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=unavailable"));
        mvc.perform(get("/api/auth/me").session(mismatch.session())).andExpect(status().isUnauthorized());

        properties.setExpectedAppId(null);
        Flow downgrade = start(new MockHttpSession());
        when(kakao.fetchIdentity(downgrade.state())).thenReturn(legacy);
        callback(downgrade, downgrade.state(), downgrade.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=unavailable"));
        mvc.perform(get("/api/auth/me").session(downgrade.session())).andExpect(status().isUnauthorized());
    }

    @Test void pendingLegacySignupCannotBypassNewlyEnabledAppVerification() throws Exception {
        Flow flow = identify(identity());
        long before = users.count();
        properties.setExpectedAppId(1234L);
        complete(flow.session(), unique("old-session")).andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void closedRegistrationRejectsNewIdentityAndAlsoRechecksPolicyBeforeCompleting() throws Exception {
        KakaoIdentity identity = identity();
        Flow pending = identify(identity);
        policy.updatePublicRegistration(false);
        complete(pending.session(), unique("closed")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(pending.session())).andExpect(status().isForbidden());
        Flow denied = start(new MockHttpSession());
        when(kakao.fetchIdentity("closed-code")).thenReturn(identity);
        callback(denied, "closed-code", denied.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=registration-closed"));
        assertThat(denied.session().getAttribute(PENDING)).isNull();
    }

    @Test void sameEmailNeverAutomaticallyLinksAnExistingAccount() throws Exception {
        KakaoIdentity identity = identity();
        AppUser existing = accounts.registerVerifiedEmailUser(unique("email"), "기존 회원", identity.email(), "test-password", "12345678");
        long before = users.count();
        Flow flow = start(new MockHttpSession());
        when(kakao.fetchIdentity("duplicate-email")).thenReturn(identity);
        callback(flow, "duplicate-email", flow.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=existing-account"));
        assertThat(users.count()).isEqualTo(before);
        assertThat(socialAccounts.existsByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())).isFalse();
        assertThat(users.findById(existing.getId()).orElseThrow().getEmail()).isEqualTo(identity.email());
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isUnauthorized());
    }

    @Test void inactiveLinkedAccountCannotSignIn() throws Exception {
        KakaoIdentity identity = identity();
        AppUser inactive = social.registerKakaoUser(identity, unique("inactive"), "비활성 회원", "test-password", "12345678");
        inactive.setActive(false);
        users.saveAndFlush(inactive);
        Flow flow = start(new MockHttpSession());
        when(kakao.fetchIdentity("inactive-code")).thenReturn(identity);
        callback(flow, "inactive-code", flow.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=existing-account"));
        mvc.perform(get("/api/auth/me").session(flow.session())).andExpect(status().isUnauthorized());
    }

    @Test void expiredOrMissingPendingSessionCannotRegister() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PENDING, pending(identity(), Instant.now().minusSeconds(1)));
        long before = users.count();
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(session)).andExpect(status().isBadRequest());
        complete(session, unique("expired")).andExpect(status().isBadRequest());
        assertThat(session.getAttribute(PENDING)).isNull();
        mvc.perform(post("/api/auth/oauth/kakao/complete").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload(unique("missing")))))
                .andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void invalidExpiredAndReplayedStateCannotFetchIdentity() throws Exception {
        Flow expired = start(new MockHttpSession());
        expired.session().setAttribute(STATE_CREATED, System.currentTimeMillis() - 301_000);
        callback(expired, "code", expired.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=invalid-state"));
        callback(expired, "code", expired.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=invalid-state"));
        Flow mismatch = start(new MockHttpSession());
        callback(mismatch, "code", "wrong-state").andExpect(header().string("Location", "https://app.test/#signup?kakao=invalid-state"));
        verify(kakao, never()).fetchIdentity(anyString());
    }

    @Test void cancellationDoesNotExchangeCodeAndCannotReuseAnOlderPendingIdentity() throws Exception {
        Flow flow = start(new MockHttpSession());
        flow.session().setAttribute(PENDING, pending(identity(), Instant.now().plusSeconds(600)));
        mvc.perform(get("/api/auth/oauth/kakao/callback").session(flow.session()).param("state", flow.state())
                        .param("error", "access_denied").param("error_description", "untrusted-secret-detail"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://app.test/#signup?kakao=cancelled"));
        assertThat(flow.session().getAttribute(PENDING)).isNull();
        verify(kakao, never()).fetchIdentity(anyString());
    }

    @Test void userCanCancelPendingRegistrationWithoutCreatingAnAccount() throws Exception {
        Flow flow = identify(identity());
        long before = users.count();
        mvc.perform(post("/api/auth/oauth/kakao/cancel").session(flow.session()).with(csrf())).andExpect(status().isNoContent());
        assertThat(flow.session().getAttribute(PENDING)).isNull();
        complete(flow.session(), unique("cancelled")).andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void completionAndCancellationRequireCsrf() throws Exception {
        Flow flow = identify(identity());
        mvc.perform(post("/api/auth/oauth/kakao/complete").session(flow.session()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload(unique("csrf"))))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/oauth/kakao/cancel").session(flow.session())).andExpect(status().isUnauthorized());
        assertThat(flow.session().getAttribute(PENDING)).isNotNull();
    }

    @Test void validationFailureKeepsPendingIdentityForCorrectionAndRetry() throws Exception {
        KakaoIdentity identity = identity();
        String occupied = unique("occupied");
        accounts.registerUser(occupied, "기존 사용자", "test-password", "12345678");
        Flow flow = identify(identity);
        long before = users.count();
        complete(flow.session(), occupied).andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(before);
        assertThat(flow.session().getAttribute(PENDING)).isNotNull();
        complete(flow.session(), unique("corrected")).andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(before + 1);
    }

    @Test void providerFailuresUseFixedSafeMessages() throws Exception {
        Flow invalidEmail = start(new MockHttpSession());
        when(kakao.fetchIdentity("bad-email")).thenThrow(new BadRequestException("untrusted-provider-detail"));
        callback(invalidEmail, "bad-email", invalidEmail.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=email-required"));
        Flow unavailable = start(new MockHttpSession());
        when(kakao.fetchIdentity("unavailable-code")).thenThrow(new IllegalStateException("mock-provider-secret"));
        callback(unavailable, "unavailable-code", unavailable.state()).andExpect(header().string("Location", "https://app.test/#signup?kakao=unavailable"));
    }

    @Test void disabledProviderCannotStartOrCompleteAndIsNotAdvertised() throws Exception {
        Flow flow = identify(identity());
        properties.setEnabled(false);
        mvc.perform(get("/api/auth/registration-options")).andExpect(jsonPath("$.socialLoginProviders").isEmpty());
        mvc.perform(get("/api/auth/oauth/kakao/start")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/auth/oauth/kakao/registration").session(flow.session())).andExpect(status().isServiceUnavailable());
        complete(flow.session(), unique("disabled")).andExpect(status().isServiceUnavailable());
    }

    private Flow start(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/oauth/kakao/start").session(session)).andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state");
        return new Flow((MockHttpSession) result.getRequest().getSession(false), state);
    }
    private MockHttpSession loginLocal(String loginId) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("loginId", loginId, "password", "test-password",
                                "secondaryPin", "12345678", "rememberDevice", false))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private org.springframework.test.web.servlet.ResultActions startLink(MockHttpSession session, String pin) throws Exception {
        return mvc.perform(post("/api/auth/oauth/kakao/link/start").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("secondaryPin", pin))));
    }
    private Flow identify(KakaoIdentity identity) throws Exception {
        Flow flow = start(new MockHttpSession());
        when(kakao.fetchIdentity(flow.state())).thenReturn(identity);
        callback(flow, flow.state(), flow.state()).andExpect(header().string("Location", "https://app.test/#oauth/kakao/complete"));
        return flow;
    }
    private Flow identifyLinked(KakaoIdentity identity) throws Exception {
        Flow flow = start(new MockHttpSession());
        when(kakao.fetchIdentity(flow.state())).thenReturn(identity);
        callback(flow, flow.state(), flow.state()).andExpect(header().string("Location", "https://app.test/#launcher"));
        return flow;
    }
    private org.springframework.test.web.servlet.ResultActions callback(Flow flow, String code, String state) throws Exception {
        return mvc.perform(get("/api/auth/oauth/kakao/callback").session(flow.session()).param("code", code).param("state", state)).andExpect(status().isFound());
    }
    private org.springframework.test.web.servlet.ResultActions complete(MockHttpSession session, String loginId) throws Exception {
        return mvc.perform(post("/api/auth/oauth/kakao/complete").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload(loginId))));
    }
    private Map<String, Object> payload(String loginId) { return Map.of("loginId", loginId, "displayName", "가입 사용자", "password", "test-password", "secondaryPin", "12345678", "rememberDevice", false); }
    private KakaoIdentity identity() { String id = unique("subject"); return new KakaoIdentity(id, id + "@example.test", "카카오 닉네임", true); }
    private KakaoPendingRegistration pending(KakaoIdentity identity, Instant expiresAt) { return new KakaoPendingRegistration(identity.providerUserId(), identity.email(), identity.displayName(), expiresAt); }
    private String unique(String prefix) { return prefix + "-" + UUID.randomUUID(); }
    private record Flow(MockHttpSession session, String state) { }
}

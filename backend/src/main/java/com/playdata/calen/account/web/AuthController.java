package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.dto.AuthKakaoRegistrationRequest;
import com.playdata.calen.account.dto.AuthLoginRequest;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationRequest;
import com.playdata.calen.account.dto.EmailSignupVerificationRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.dto.EmailVerificationResendResponse;
import com.playdata.calen.account.dto.EmailVerificationResultResponse;
import com.playdata.calen.account.dto.EmailVerificationStartResponse;
import com.playdata.calen.account.dto.ProfilePasswordChangeRequest;
import com.playdata.calen.account.dto.ProfilePrivacyAccessVerifyRequest;
import com.playdata.calen.account.dto.ProfileEmailVerificationRequest;
import com.playdata.calen.account.dto.ProfileSecondaryPinChangeRequest;
import com.playdata.calen.account.dto.ProfileSecondaryPinVerifyRequest;
import com.playdata.calen.account.dto.PublicRegistrationOptionsResponse;
import com.playdata.calen.account.dto.KakaoRegistrationSessionResponse;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.security.AuthenticationCommitFence;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.AccountEmailVerificationService;
import com.playdata.calen.account.service.EmailVerificationService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.account.service.LoginAttemptService;
import com.playdata.calen.account.service.RegistrationPolicyService;
import com.playdata.calen.account.service.SocialAccountService;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoLinkStateChangedException;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.account.social.KakaoPendingRegistration;
import com.playdata.calen.account.withdrawal.KakaoWithdrawalReconnectGuard;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import com.playdata.calen.account.security.AccountRememberMeTokens;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String KAKAO_STATE_SESSION_KEY = "CALEN_KAKAO_OAUTH_STATE";
    private static final String KAKAO_STATE_CREATED_SESSION_KEY = "CALEN_KAKAO_OAUTH_STATE_CREATED";
    private static final String KAKAO_PENDING_SESSION_KEY = "CALEN_KAKAO_PENDING_REGISTRATION";
    private static final String KAKAO_LINK_USER_SESSION_KEY = "CALEN_KAKAO_LINK_USER";
    private static final String KAKAO_LINK_PROOF_SESSION_KEY = "CALEN_KAKAO_LINK_SECURITY_PROOF";
    private static final long KAKAO_STATE_VALIDITY_MILLIS = 5 * 60 * 1000L;
    private static final long KAKAO_REGISTRATION_VALIDITY_SECONDS = 10 * 60L;

    private final AppUserService appUserService;
    private final AccountEmailVerificationService accountEmailVerificationService;
    private final EmailVerificationService emailVerificationService;
    private final LoginAttemptService loginAttemptService;
    private final LoginAuditLogService loginAuditLogService;
    private final RegistrationPolicyService registrationPolicyService;
    private final AuthenticationManager authenticationManager;
    private final AuthenticationCommitFence authenticationCommitFence;
    private final SecurityContextRepository securityContextRepository;
    private final PersistentTokenBasedRememberMeServices rememberMeServices;
    private final AccountRememberMeTokens persistentTokenRepository;
    private final SecondaryPinSessionSupport secondaryPinSessionSupport;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final SocialAccountService socialAccountService;
    private final KakaoWithdrawalReconnectGuard kakaoWithdrawalReconnectGuard;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()
        );
    }

    @GetMapping("/registration-options")
    public PublicRegistrationOptionsResponse registrationOptions() {
        return registrationPolicyService.getPublicOptions();
    }

    @PostMapping("/login")
    public AppUserResponse login(
            @Valid @RequestBody AuthLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String normalizedLoginId = request.loginId().trim();
        String normalizedSecondaryPin = request.secondaryPin().trim();
        String clientIp = resolveClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        try {
            loginAttemptService.ensureAllowed(clientIp);
        } catch (TooManyRequestsException exception) {
            loginAuditLogService.record(
                    normalizedLoginId,
                    clientIp,
                    userAgent,
                    LoginAuditStatus.BLOCKED,
                    exception.getMessage(),
                    null
            );
            throw exception;
        }

        boolean[] signInStarted = { false };
        try {
            Authentication authentication = authenticate(normalizedLoginId, request.password());
            AppUserPrincipal verifiedPrincipal = (AppUserPrincipal) authentication.getPrincipal();
            AppUser authenticatedUser = appUserService.getRequiredUser(verifiedPrincipal.userId());
            appUserService.ensureSecondaryPinMatches(authenticatedUser, normalizedSecondaryPin);
            AppUserResponse result = authenticationCommitFence.withLocal(verifiedPrincipal, authenticatedUser.getSecondaryPinHash(), current -> {
                loginAuditLogService.record(normalizedLoginId, clientIp, userAgent, LoginAuditStatus.SUCCESS,
                        "로그인 성공", current);
                signInStarted[0] = true;
                signIn(authenticatedPrincipal(current), request.rememberDevice(), httpRequest, httpResponse);
                secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, normalizedSecondaryPin);
                return appUserService.toResponse(current);
            });
            // Redis-backed rate-limit cleanup need not hold the account's DB lock.
            loginAttemptService.recordSuccess(clientIp);
            return result;
        } catch (com.playdata.calen.account.security.SecondaryPinMismatchException exception) {
            if (signInStarted[0]) abortIncompleteSignIn(httpRequest, httpResponse);
            loginAttemptService.recordFailure(clientIp);
            AppUser user = appUserService.findActiveUserByLoginId(normalizedLoginId).orElse(null);
            loginAuditLogService.record(
                    normalizedLoginId,
                    clientIp,
                    userAgent,
                    LoginAuditStatus.BAD_SECONDARY_PIN,
                    "\uB85C\uADF8\uC778 \uC815\uBCF4\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.",
                    user
            );
            throw exception;
        } catch (AuthenticationException exception) {
            if (signInStarted[0]) abortIncompleteSignIn(httpRequest, httpResponse);
            loginAttemptService.recordFailure(clientIp);
            loginAuditLogService.record(
                    normalizedLoginId,
                    clientIp,
                    userAgent,
                    LoginAuditStatus.BAD_CREDENTIALS,
                    "\uB85C\uADF8\uC778 \uC815\uBCF4\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.",
                    null
            );
            throw exception;
        } catch (RuntimeException exception) {
            if (signInStarted[0]) abortIncompleteSignIn(httpRequest, httpResponse);
            throw exception;
        }
    }

    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public EmailVerificationStartResponse register(@Valid @RequestBody AuthRegisterRequest request) {
        registrationPolicyService.requirePublicRegistrationEnabled();
        return emailVerificationService.start(request);
    }

    @PostMapping("/email-verification/verify")
    public EmailVerificationResultResponse verifyEmail(
            @Valid @RequestBody EmailSignupVerificationRequest request
    ) {
        return emailVerificationService.verify(request.token(), request.secondaryPin());
    }

    @PostMapping("/email-verification/resend")
    public EmailVerificationResendResponse resendEmail(
            @Valid @RequestBody EmailVerificationResendRequest request
    ) {
        return emailVerificationService.resend(request);
    }

    @PostMapping("/profile/email-verification")
    public EmailVerificationStartResponse startProfileEmailVerification(@Valid @RequestBody ProfileEmailVerificationRequest request,
            Authentication authentication) {
        return accountEmailVerificationService.start(requireAuthenticatedUserId(authentication), request.email(), request.secondaryPin());
    }

    @PostMapping("/email-verification/verify-profile")
    public EmailVerificationResultResponse verifyProfileEmail(@Valid @RequestBody EmailVerificationRequest request) {
        return accountEmailVerificationService.verify(request.token());
    }

    @GetMapping("/oauth/kakao/start")
    public void startKakaoLogin(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) throws IOException {
        if (!kakaoOAuthClient.isConfigured()) {
            throw new ServiceUnavailableException("카카오 로그인이 아직 설정되지 않았습니다.");
        }
        String state = createOAuthState();
        HttpSession session = httpRequest.getSession(true);
        session.removeAttribute(KAKAO_PENDING_SESSION_KEY);
        session.removeAttribute(KAKAO_LINK_USER_SESSION_KEY);
        session.removeAttribute(KAKAO_LINK_PROOF_SESSION_KEY);
        session.setAttribute(KAKAO_STATE_SESSION_KEY, state);
        session.setAttribute(KAKAO_STATE_CREATED_SESSION_KEY, System.currentTimeMillis());
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.sendRedirect(kakaoOAuthClient.authorizationUrl(state));
    }

    @GetMapping("/oauth/kakao/link")
    public ResponseEntity<Map<String, Boolean>> kakaoLinkStatus(Authentication authentication) {
        Long userId = requireAuthenticatedUserId(authentication);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                "configured", kakaoOAuthClient.isConfigured(), "linked", socialAccountService.isKakaoLinked(userId)));
    }

    @PostMapping("/oauth/kakao/link/start")
    public ResponseEntity<Void> startKakaoLink(@Valid @RequestBody ProfileSecondaryPinVerifyRequest request,
            Authentication authentication, HttpServletRequest httpRequest) {
        Long userId = requireAuthenticatedUserId(authentication);
        if (!kakaoOAuthClient.isConfigured()) throw new ServiceUnavailableException("카카오 로그인이 아직 설정되지 않았습니다.");
        HttpSession existingSession = httpRequest.getSession(false);
        if (existingSession != null) {
            existingSession.removeAttribute(KAKAO_LINK_USER_SESSION_KEY);
            existingSession.removeAttribute(KAKAO_LINK_PROOF_SESSION_KEY);
            existingSession.removeAttribute(KAKAO_STATE_SESSION_KEY);
            existingSession.removeAttribute(KAKAO_STATE_CREATED_SESSION_KEY);
        }
        String version = appUserService.verifySecondaryPinForKakaoLink(userId, request.secondaryPin());
        if (socialAccountService.isKakaoLinked(userId)) throw new BadRequestException("이미 카카오 계정이 연결되어 있습니다.");
        HttpSession session = httpRequest.getSession(true);
        session.removeAttribute(KAKAO_PENDING_SESSION_KEY);
        session.setAttribute(KAKAO_STATE_SESSION_KEY, createOAuthState());
        session.setAttribute(KAKAO_STATE_CREATED_SESSION_KEY, System.currentTimeMillis());
        session.setAttribute(KAKAO_LINK_USER_SESSION_KEY, userId);
        session.setAttribute(KAKAO_LINK_PROOF_SESSION_KEY, secondaryPinSessionSupport.sealKakaoLinkVersion(userId, version));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @GetMapping("/oauth/kakao/link/authorize")
    public void authorizeKakaoLink(Authentication authentication, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        Long userId = requireAuthenticatedUserId(authentication);
        HttpSession session = httpRequest.getSession(false);
        Object state = session != null ? session.getAttribute(KAKAO_STATE_SESSION_KEY) : null;
        Object created = session != null ? session.getAttribute(KAKAO_STATE_CREATED_SESSION_KEY) : null;
        Object linkUser = session != null ? session.getAttribute(KAKAO_LINK_USER_SESSION_KEY) : null;
        Object linkProof = session != null ? session.getAttribute(KAKAO_LINK_PROOF_SESSION_KEY) : null;
        if (!(state instanceof String value) || !(created instanceof Number time)
                || !(linkUser instanceof Long owner) || !owner.equals(userId)
                || secondaryPinSessionSupport.openKakaoLinkVersion(userId, linkProof) == null
                || System.currentTimeMillis() - time.longValue() > KAKAO_STATE_VALIDITY_MILLIS) {
            redirectToFrontend(httpResponse, "profile?kakao-link=invalid-state");
            return;
        }
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.sendRedirect(kakaoOAuthClient.authorizationUrl(value));
    }

    @GetMapping("/oauth/kakao/callback")
    public void kakaoCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) throws IOException {
        HttpSession callbackSession = httpRequest.getSession(false);
        Object linkUser = callbackSession != null ? callbackSession.getAttribute(KAKAO_LINK_USER_SESSION_KEY) : null;
        Object linkProof = callbackSession != null ? callbackSession.getAttribute(KAKAO_LINK_PROOF_SESSION_KEY) : null;
        if (callbackSession != null) {
            callbackSession.removeAttribute(KAKAO_LINK_USER_SESSION_KEY);
            callbackSession.removeAttribute(KAKAO_LINK_PROOF_SESSION_KEY);
        }
        clearPendingKakaoRegistration(httpRequest);
        if (!consumeOAuthState(httpRequest, state)) {
            redirectToFrontend(httpResponse, linkUser != null ? "profile?kakao-link=invalid-state" : "signup?kakao=invalid-state");
            return;
        }
        if (error != null || code == null || code.isBlank()) {
            String result = "access_denied".equals(error) ? "cancelled" : "failed";
            redirectToFrontend(httpResponse, linkUser != null ? "profile?kakao-link=" + result : "signup?kakao=" + result);
            return;
        }

        try {
            if (linkUser != null) {
                if (!(linkUser instanceof Long owner) || !owner.equals(requireAuthenticatedUserId(authentication))) {
                    redirectToFrontend(httpResponse, "profile?kakao-link=invalid-state");
                    return;
                }
                String version = secondaryPinSessionSupport.openKakaoLinkVersion(owner, linkProof);
                if (version == null) {
                    redirectToFrontend(httpResponse, "profile?kakao-link=invalid-state");
                    return;
                }
                KakaoIdentity identity = kakaoOAuthClient.fetchIdentity(code);
                if (kakaoWithdrawalReconnectGuard.requeueIfWithdrawing(identity)) {
                    redirectToFrontend(httpResponse, "profile?kakao-link=unavailable");
                    return;
                }
                socialAccountService.linkKakaoToExistingUser(owner, identity, version);
                redirectToFrontend(httpResponse, "profile?kakao-link=connected");
                return;
            }
            KakaoIdentity identity = kakaoOAuthClient.fetchIdentity(code);
            if (kakaoWithdrawalReconnectGuard.requeueIfWithdrawing(identity)) {
                redirectToFrontend(httpResponse, "signup?kakao=unavailable");
                return;
            }
            Optional<AppUser> linkedUser = socialAccountService.findActiveUser(
                    SocialLoginProvider.KAKAO,
                    identity.providerUserId()
            );
            if (linkedUser.isPresent()) {
                AppUser user = linkedUser.get();
                boolean[] signInStarted = { false };
                try {
                    authenticationCommitFence.withKakao(user.getId(), identity, current -> {
                        loginAuditLogService.record(current.getLoginId(), resolveClientIp(httpRequest),
                                httpRequest.getHeader("User-Agent"), LoginAuditStatus.SUCCESS, "카카오 로그인 성공", current);
                        signInStarted[0] = true;
                        signIn(authenticatedPrincipal(current), false, httpRequest, httpResponse);
                        return null;
                    });
                } catch (RuntimeException exception) {
                    if (signInStarted[0]) abortIncompleteSignIn(httpRequest, httpResponse);
                    throw exception;
                }
                redirectToFrontend(httpResponse, "launcher");
                return;
            }

            if (!registrationPolicyService.isPublicRegistrationEnabled()) {
                redirectToFrontend(httpResponse, "signup?kakao=registration-closed");
                return;
            }
            if (appUserService.findUserByEmail(identity.email()).isPresent()) {
                redirectToFrontend(httpResponse, "signup?kakao=existing-account");
                return;
            }

            httpRequest.getSession(true).setAttribute(
                    KAKAO_PENDING_SESSION_KEY,
                    new KakaoPendingRegistration(identity.providerUserId(), identity.email(), identity.displayName(),
                            Instant.now().plusSeconds(KAKAO_REGISTRATION_VALIDITY_SECONDS), identity.providerAppId())
            );
            redirectToFrontend(httpResponse, "oauth/kakao/complete");
        } catch (KakaoLinkStateChangedException exception) {
            redirectToFrontend(httpResponse, "profile?kakao-link=invalid-state");
        } catch (BadRequestException exception) {
            redirectToFrontend(httpResponse, linkUser != null ? "profile?kakao-link=conflict" : "signup?kakao=email-required");
        } catch (RuntimeException exception) {
            redirectToFrontend(httpResponse, linkUser != null ? "profile?kakao-link=unavailable" : "signup?kakao=unavailable");
        }
    }

    @GetMapping("/oauth/kakao/registration")
    public ResponseEntity<KakaoRegistrationSessionResponse> kakaoRegistration(HttpServletRequest request) {
        requireKakaoRegistrationAvailable();
        KakaoPendingRegistration pending = requirePendingKakaoRegistration(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new KakaoRegistrationSessionResponse(pending.email(), pending.displayName(), pending.expiresAt()));
    }

    @PostMapping("/oauth/kakao/cancel")
    public ResponseEntity<Void> cancelKakaoRegistration(HttpServletRequest request) {
        clearPendingKakaoRegistration(request);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(KAKAO_STATE_SESSION_KEY);
            session.removeAttribute(KAKAO_STATE_CREATED_SESSION_KEY);
            session.removeAttribute(KAKAO_LINK_USER_SESSION_KEY);
            session.removeAttribute(KAKAO_LINK_PROOF_SESSION_KEY);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/oauth/kakao/complete")
    public AppUserResponse completeKakaoRegistration(
            @Valid @RequestBody AuthKakaoRegistrationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        requireKakaoRegistrationAvailable();
        KakaoPendingRegistration pending = requirePendingKakaoRegistration(httpRequest);

        KakaoIdentity identity = new KakaoIdentity(
                pending.providerUserId(),
                pending.email(),
                pending.displayName(),
                true,
                pending.providerAppId()
        );
        boolean legacyLoginId = org.springframework.util.StringUtils.hasText(request.loginId());
        boolean legacyPassword = org.springframework.util.StringUtils.hasText(request.password());
        if (legacyLoginId != legacyPassword) {
            throw new BadRequestException("일반 로그인 정보는 아이디와 비밀번호를 함께 입력해야 합니다.");
        }
        AppUser createdUser = legacyLoginId
                ? socialAccountService.registerKakaoUser(identity, request.loginId(), request.displayName(), request.password(), request.secondaryPin())
                : socialAccountService.registerKakaoOnlyUser(identity, request.displayName(), request.secondaryPin());
        clearPendingKakaoRegistration(httpRequest);
        boolean[] signInStarted = { false };
        try {
            return authenticationCommitFence.withKakao(createdUser.getId(), identity, current -> {
                loginAuditLogService.record(current.getLoginId(), resolveClientIp(httpRequest),
                        httpRequest.getHeader("User-Agent"), LoginAuditStatus.SUCCESS, "카카오 가입 후 로그인 성공", current);
                signInStarted[0] = true;
                signIn(authenticatedPrincipal(current), request.rememberDevice(), httpRequest, httpResponse);
                secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, request.secondaryPin().trim());
                return appUserService.toResponse(current);
            });
        } catch (RuntimeException exception) {
            if (signInStarted[0]) abortIncompleteSignIn(httpRequest, httpResponse);
            throw exception;
        }
    }

    private void requireKakaoRegistrationAvailable() {
        registrationPolicyService.requirePublicRegistrationEnabled();
        if (!kakaoOAuthClient.isConfigured()) {
            throw new ServiceUnavailableException("카카오 로그인이 아직 설정되지 않았습니다.");
        }
    }

    private KakaoPendingRegistration requirePendingKakaoRegistration(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(KAKAO_PENDING_SESSION_KEY) instanceof KakaoPendingRegistration pending
                && pending.expiresAt() != null && pending.expiresAt().isAfter(Instant.now())) {
            return pending;
        }
        clearPendingKakaoRegistration(request);
        throw new BadRequestException("카카오 가입 인증이 만료되었거나 취소되었습니다. 카카오로 다시 인증해 주세요.");
    }

    private void clearPendingKakaoRegistration(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.removeAttribute(KAKAO_PENDING_SESSION_KEY);
    }

    @GetMapping("/me")
    public ResponseEntity<AppUserResponse> me(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (!(authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            // An anonymous session may hold an in-progress OAuth state or signup.
            // Checking login status must not destroy that unrelated session data.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(appUserService.toResponse(appUserService.getRequiredUser(principal.userId())));
        } catch (Exception exception) {
            clearAuthentication(authentication, httpRequest, httpResponse);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        clearAuthentication(authentication, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/profile/verify-secondary-pin")
    public ResponseEntity<Void> verifyProfileSecondaryPin(
            @Valid @RequestBody ProfileSecondaryPinVerifyRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        appUserService.verifySecondaryPin(requireAuthenticatedUserId(authentication), request.secondaryPin());
        secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, request.secondaryPin().trim());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/profile/verify-privacy-access")
    public ResponseEntity<Void> verifyProfilePrivacyAccess(
            @Valid @RequestBody ProfilePrivacyAccessVerifyRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String verifiedSecondaryPin = appUserService.verifyPrivacyAccess(
                requireAuthenticatedUserId(authentication),
                request.password(),
                request.secondaryPin()
        );
        secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, verifiedSecondaryPin);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/profile/password")
    public ResponseEntity<Void> updateProfilePassword(
            @Valid @RequestBody ProfilePasswordChangeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        appUserService.updatePassword(
                requireAuthenticatedUserId(authentication),
                request.secondaryPin(),
                request.newPassword()
        );
        revokeRememberMeTokens(authentication, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/profile/secondary-pin")
    public ResponseEntity<Void> updateProfileSecondaryPin(
            @Valid @RequestBody ProfileSecondaryPinChangeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String updatedSecondaryPin = appUserService.updateSecondaryPin(
                requireAuthenticatedUserId(authentication),
                request.secondaryPin(),
                request.newSecondaryPin()
        );
        secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, updatedSecondaryPin);
        revokeRememberMeTokens(authentication, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    private Authentication authenticate(String loginId, String password) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(loginId.trim(), password)
        );
    }

    private Authentication authenticatedPrincipal(AppUser user) {
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private String createOAuthState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean consumeOAuthState(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object expectedState = session.getAttribute(KAKAO_STATE_SESSION_KEY);
        Object createdAt = session.getAttribute(KAKAO_STATE_CREATED_SESSION_KEY);
        session.removeAttribute(KAKAO_STATE_SESSION_KEY);
        session.removeAttribute(KAKAO_STATE_CREATED_SESSION_KEY);
        if (!(expectedState instanceof String expected)
                || !(createdAt instanceof Number created)
                || state == null
                || System.currentTimeMillis() - created.longValue() > KAKAO_STATE_VALIDITY_MILLIS) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void redirectToFrontend(HttpServletResponse response, String route) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String baseUrl = kakaoOAuthProperties.getFrontendBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        response.sendRedirect(baseUrl.trim().replaceAll("/+$", "") + "/#" + route);
    }

    private Long requireAuthenticatedUserId(Authentication authentication) {
        if (!(authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new org.springframework.security.authentication.InsufficientAuthenticationException("\uB85C\uADF8\uC778\uC774 \uD544\uC694\uD569\uB2C8\uB2E4.");
        }
        return principal.userId();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void signIn(
            Authentication authentication,
            boolean rememberDevice,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        if (rememberDevice) {
            rememberMeServices.loginSuccess(httpRequest, httpResponse, authentication);
        } else {
            rememberMeServices.logout(httpRequest, httpResponse, authentication);
        }
    }

    private void abortIncompleteSignIn(HttpServletRequest request, HttpServletResponse response) {
        rememberMeServices.logout(request, response, null);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try { session.invalidate(); } catch (IllegalStateException ignored) { /* Already invalidated. */ }
        }
    }

    private void revokeRememberMeTokens(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            persistentTokenRepository.removeUserTokensById(principal.userId());
        }
        rememberMeServices.logout(httpRequest, httpResponse, authentication);
    }

    private void clearAuthentication(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        rememberMeServices.logout(httpRequest, httpResponse, authentication);
        SecurityContextHolder.clearContext();
        if (httpRequest.getSession(false) != null) {
            httpRequest.getSession(false).invalidate();
        }
    }
}

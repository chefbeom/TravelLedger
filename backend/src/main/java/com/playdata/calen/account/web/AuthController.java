package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.dto.AuthKakaoRegistrationRequest;
import com.playdata.calen.account.dto.AuthLoginRequest;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.dto.EmailVerificationResendResponse;
import com.playdata.calen.account.dto.EmailVerificationResultResponse;
import com.playdata.calen.account.dto.EmailVerificationStartResponse;
import com.playdata.calen.account.dto.ProfilePasswordChangeRequest;
import com.playdata.calen.account.dto.ProfilePrivacyAccessVerifyRequest;
import com.playdata.calen.account.dto.ProfileSecondaryPinChangeRequest;
import com.playdata.calen.account.dto.ProfileSecondaryPinVerifyRequest;
import com.playdata.calen.account.dto.PublicRegistrationOptionsResponse;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.EmailVerificationService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.account.service.LoginAttemptService;
import com.playdata.calen.account.service.RegistrationPolicyService;
import com.playdata.calen.account.service.SocialAccountService;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.account.social.KakaoPendingRegistration;
import com.playdata.calen.common.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
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
    private static final long KAKAO_STATE_VALIDITY_MILLIS = 5 * 60 * 1000L;

    private final AppUserService appUserService;
    private final EmailVerificationService emailVerificationService;
    private final LoginAttemptService loginAttemptService;
    private final LoginAuditLogService loginAuditLogService;
    private final RegistrationPolicyService registrationPolicyService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final PersistentTokenBasedRememberMeServices rememberMeServices;
    private final PersistentTokenRepository persistentTokenRepository;
    private final SecondaryPinSessionSupport secondaryPinSessionSupport;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final SocialAccountService socialAccountService;
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

        Authentication authentication;
        AppUser authenticatedUser;
        try {
            authentication = authenticate(normalizedLoginId, request.password());
            authenticatedUser = appUserService.getRequiredUser(((AppUserPrincipal) authentication.getPrincipal()).userId());
            appUserService.ensureSecondaryPinMatches(authenticatedUser, normalizedSecondaryPin);
        } catch (com.playdata.calen.account.security.SecondaryPinMismatchException exception) {
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
        }

        loginAttemptService.recordSuccess(clientIp);
        loginAuditLogService.record(
                normalizedLoginId,
                clientIp,
                userAgent,
                LoginAuditStatus.SUCCESS,
                "\uB85C\uADF8\uC778 \uC131\uACF5",
                authenticatedUser
        );
        signIn(authentication, request.rememberDevice(), httpRequest, httpResponse);
        secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, normalizedSecondaryPin);
        return appUserService.toResponse(authenticatedUser);
    }

    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public EmailVerificationStartResponse register(@Valid @RequestBody AuthRegisterRequest request) {
        registrationPolicyService.requirePublicRegistrationEnabled();
        return emailVerificationService.start(request);
    }

    @PostMapping("/email-verification/verify")
    public EmailVerificationResultResponse verifyEmail(
            @Valid @RequestBody EmailVerificationRequest request
    ) {
        return emailVerificationService.verify(request.token());
    }

    @PostMapping("/email-verification/resend")
    public EmailVerificationResendResponse resendEmail(
            @Valid @RequestBody EmailVerificationResendRequest request
    ) {
        return emailVerificationService.resend(request);
    }

    @GetMapping("/oauth/kakao/start")
    public void startKakaoLogin(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) throws IOException {
        String state = createOAuthState();
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(KAKAO_STATE_SESSION_KEY, state);
        session.setAttribute(KAKAO_STATE_CREATED_SESSION_KEY, System.currentTimeMillis());
        httpResponse.sendRedirect(kakaoOAuthClient.authorizationUrl(state));
    }

    @GetMapping("/oauth/kakao/callback")
    public void kakaoCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) throws IOException {
        if (!consumeOAuthState(httpRequest, state) || error != null || code == null || code.isBlank()) {
            redirectToFrontend(httpResponse, "signup");
            return;
        }

        try {
            KakaoIdentity identity = kakaoOAuthClient.fetchIdentity(code);
            Optional<AppUser> linkedUser = socialAccountService.findActiveUser(
                    SocialLoginProvider.KAKAO,
                    identity.providerUserId()
            );
            if (linkedUser.isPresent()) {
                AppUser user = linkedUser.get();
                Authentication authentication = authenticatedPrincipal(user);
                signIn(authentication, false, httpRequest, httpResponse);
                loginAuditLogService.record(
                        user.getLoginId(),
                        resolveClientIp(httpRequest),
                        httpRequest.getHeader("User-Agent"),
                        LoginAuditStatus.SUCCESS,
                        "카카오 로그인 성공",
                        user
                );
                redirectToFrontend(httpResponse, "launcher");
                return;
            }

            if (!registrationPolicyService.isPublicRegistrationEnabled()
                    || appUserService.findUserByEmail(identity.email()).isPresent()) {
                redirectToFrontend(httpResponse, "signup");
                return;
            }

            httpRequest.getSession(true).setAttribute(
                    KAKAO_PENDING_SESSION_KEY,
                    new KakaoPendingRegistration(identity.providerUserId(), identity.email(), identity.displayName())
            );
            redirectToFrontend(httpResponse, "oauth/kakao/complete");
        } catch (RuntimeException exception) {
            redirectToFrontend(httpResponse, "signup");
        }
    }

    @PostMapping("/oauth/kakao/complete")
    public AppUserResponse completeKakaoRegistration(
            @Valid @RequestBody AuthKakaoRegistrationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        registrationPolicyService.requirePublicRegistrationEnabled();
        HttpSession session = httpRequest.getSession(false);
        if (session == null || !(session.getAttribute(KAKAO_PENDING_SESSION_KEY) instanceof KakaoPendingRegistration pending)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "카카오 가입 세션이 만료되었습니다. 다시 시도해 주세요."
            );
        }

        KakaoIdentity identity = new KakaoIdentity(
                pending.providerUserId(),
                pending.email(),
                pending.displayName(),
                true
        );
        AppUser createdUser = socialAccountService.registerKakaoUser(
                identity,
                request.loginId(),
                request.displayName(),
                request.password(),
                request.secondaryPin()
        );
        session.removeAttribute(KAKAO_PENDING_SESSION_KEY);
        Authentication authentication = authenticate(createdUser.getLoginId(), request.password());
        signIn(authentication, request.rememberDevice(), httpRequest, httpResponse);
        secondaryPinSessionSupport.storeVerifiedSecondaryPin(httpRequest, request.secondaryPin().trim());
        loginAuditLogService.record(
                createdUser.getLoginId(),
                resolveClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                LoginAuditStatus.SUCCESS,
                "카카오 가입 후 로그인 성공",
                createdUser
        );
        return appUserService.toResponse(createdUser);
    }

    @GetMapping("/me")
    public ResponseEntity<AppUserResponse> me(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (!(authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            clearAuthentication(authentication, httpRequest, httpResponse);
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

    private void revokeRememberMeTokens(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            persistentTokenRepository.removeUserTokens(principal.loginId());
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

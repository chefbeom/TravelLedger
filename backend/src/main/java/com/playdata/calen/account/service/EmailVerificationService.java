package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.EmailVerificationToken;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.dto.EmailVerificationResendResponse;
import com.playdata.calen.account.dto.EmailVerificationResultResponse;
import com.playdata.calen.account.dto.EmailVerificationStartResponse;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AuthEndpointPolicy;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EmailVerificationService {

    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_SIGNUP_PIN_FAILURES = 5;
    private static final Duration SIGNUP_PIN_WINDOW = Duration.ofMinutes(15);

    private final AppUserService appUserService;
    private final AppUserRepository appUserRepository;
    private final AccountSetupService accountSetupService;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailSignupExpiryService signupExpiry;
    private final EmailSignupDatabaseClock signupClock;
    private final EmailVerificationSender emailVerificationSender;
    private final EntityManager entities;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.email-verification.enabled:true}")
    private boolean enabled;

    @Value("${app.auth.email-verification.base-url:}")
    private String baseUrl;

    @Value("${app.auth.email-verification.token-validity:30m}")
    private Duration tokenValidity;

    @Transactional
    public EmailVerificationStartResponse start(AuthRegisterRequest request) {
        ensureConfigured();
        AppUser user = appUserService.registerPendingEmailUser(
                request.loginId(),
                request.displayName(),
                request.email(),
                request.password(),
                request.secondaryPin()
        );
        VerificationToken token = issueToken(user, true);
        emailVerificationSender.send(
                user.getEmail(),
                buildVerificationUrl(token.rawValue()),
                tokenValidity
        );
        return new EmailVerificationStartResponse(
                true,
                maskEmail(user.getEmail()),
                tokenValidity.toSeconds(),
                "인증 메일을 보냈습니다. 이메일의 링크를 열어 가입을 완료해 주세요."
        );
    }

    @Transactional(noRollbackFor = {SignupPinMismatch.class, SignupPinLimited.class})
    public EmailVerificationResultResponse verify(String rawToken, String secondaryPin) {
        ensureConfigured();
        String tokenHash = hashToken(rawToken);
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        // Match admin deactivation and resend: lock the user before the token.
        AppUser user = appUserRepository.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        EmailVerificationToken token = tokenRepository.findForUpdateByTokenHash(tokenHash)
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        entities.refresh(token, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = token.isCleanupEligible() ? signupClock.now() : LocalDateTime.now();
        if (token.getUsedAt() != null || !now.isBefore(token.getExpiresAt())) {
            throw new BadRequestException("만료되었거나 이미 사용한 이메일 인증 링크입니다.");
        }

        if (!user.isActive() && token.isCleanupEligible()
                && !token.getCreatedAt().isAfter(now.minus(EmailSignupExpiryService.RETENTION))) {
            throw new BadRequestException("가입 신청이 만료되었습니다. 다시 가입해 주세요.");
        }

        // A registration token must never reactivate a previously verified account that
        // an administrator (or a future withdrawal workflow) has disabled.
        if (user.isEmailVerified()) {
            throw new BadRequestException("이미 처리된 이메일 인증 링크입니다.");
        }
        // The email recipient must also know the signup PIN; opening a link alone
        // must not activate credentials chosen by a different person.
        // The shared DB row is already locked. This survives Redis outages, other app
        // nodes and link reissues; the existing account-wide limiter is still applied.
        LocalDateTime attemptNow = token.isCleanupEligible() ? now : signupClock.now();
        if (user.getSignupPinBlockedUntil() != null && attemptNow.isBefore(user.getSignupPinBlockedUntil())) {
            throw new SignupPinLimited();
        }
        switch (appUserService.checkSignupPin(user, secondaryPin)) {
            case MISMATCH -> {
                if (recordSignupPinFailure(user, attemptNow)) throw new SignupPinLimited();
                throw new SignupPinMismatch();
            }
            case LIMITED -> {
                // Preserve any account-wide limit reached on this node or in Redis.
                user.setSignupPinFailures(MAX_SIGNUP_PIN_FAILURES);
                user.setSignupPinWindowStartedAt(attemptNow);
                user.setSignupPinBlockedUntil(attemptNow.plus(SIGNUP_PIN_WINDOW));
                throw new SignupPinLimited();
            }
            case MATCH -> { }
        }
        clearSignupPinFailures(user);
        user.setEmailVerified(true);
        user.setActive(true);
        token.setUsedAt(now);
        tokenRepository.invalidateOpenTokens(user.getId(), now);
        accountSetupService.initializeDefaults(user);
        return new EmailVerificationResultResponse(true, "이메일 인증이 완료되었습니다. 이제 로그인할 수 있습니다.");
    }

    @Transactional
    public EmailVerificationResendResponse resend(EmailVerificationResendRequest request) {
        ensureConfigured();
        String normalizedEmail = appUserService.normalizeEmailFormat(request.email());
        Optional<Long> userId = appUserRepository.findIdByEmailIgnoreCase(normalizedEmail);
        if (userId.isEmpty()) {
            return acceptedResendResponse();
        }
        AppUser user = appUserRepository.lockForAccountSecurityUpdate(userId.get()).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user == null || user.getEmail() == null || !user.getEmail().equalsIgnoreCase(normalizedEmail)) {
            return acceptedResendResponse();
        }
        if (user.isEmailVerified()) {
            return acceptedResendResponse();
        }

        Optional<EmailVerificationToken> latest = tokenRepository
                .findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId());
        latest.ifPresent(token -> entities.refresh(token, LockModeType.PESSIMISTIC_WRITE));
        boolean cleanupEligible = latest.map(EmailVerificationToken::isCleanupEligible).orElse(false);
        LocalDateTime now = cleanupEligible ? signupClock.now() : LocalDateTime.now();
        // Admin deactivation invalidates all pending tokens. Do not silently restart a
        // cancelled pending registration, unless an administrator explicitly re-enables it.
        if (latest.isEmpty() && !user.isActive()) return acceptedResendResponse();
        if (!user.isActive() && latest.isPresent() && latest.get().isCleanupEligible()
                && !latest.get().getCreatedAt().isAfter(now.minus(EmailSignupExpiryService.RETENTION))) {
            signupExpiry.expireIfEligible(user.getId(), now);
            return acceptedResendResponse();
        }
        if (latest.isPresent()
                && Duration.between(latest.get().getCreatedAt(), now).compareTo(RESEND_COOLDOWN) < 0) {
            // The unauthenticated endpoint must not reveal that an email has a
            // pending registration. Keep the existing token and suppress the send.
            return acceptedResendResponse();
        }

        // Do not invalidate the previous usable link if SMTP rejects or times out.
        // A public resend response must not reveal whether the address exists.
        String rawToken = createRawToken();
        try {
            emailVerificationSender.send(user.getEmail(), buildVerificationUrl(rawToken), tokenValidity);
        } catch (RuntimeException failure) {
            log.warn("Signup verification resend delivery failed");
            return acceptedResendResponse();
        }
        issueToken(user, cleanupEligible, rawToken);
        return acceptedResendResponse();
    }

    private VerificationToken issueToken(AppUser user, boolean cleanupEligible) {
        return issueToken(user, cleanupEligible, createRawToken());
    }

    private VerificationToken issueToken(AppUser user, boolean cleanupEligible, String rawValue) {
        LocalDateTime now = cleanupEligible ? signupClock.now() : LocalDateTime.now();
        tokenRepository.invalidateOpenTokens(user.getId(), now);
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(hashToken(rawValue));
        token.setCreatedAt(now);
        token.setCleanupEligible(cleanupEligible);
        token.setExpiresAt(now.plus(tokenValidity));
        tokenRepository.save(token);
        return new VerificationToken(rawValue);
    }

    private boolean recordSignupPinFailure(AppUser user, LocalDateTime now) {
        LocalDateTime started = user.getSignupPinWindowStartedAt();
        int count = user.getSignupPinFailures();
        if (started == null || !now.isBefore(started.plus(SIGNUP_PIN_WINDOW)) || count < 0) {
            started = now;
            count = 0;
        }
        int next = count >= MAX_SIGNUP_PIN_FAILURES ? MAX_SIGNUP_PIN_FAILURES : count + 1;
        user.setSignupPinWindowStartedAt(started);
        user.setSignupPinFailures(next);
        if (next >= MAX_SIGNUP_PIN_FAILURES) {
            user.setSignupPinBlockedUntil(now.plus(SIGNUP_PIN_WINDOW));
            return true;
        }
        user.setSignupPinBlockedUntil(null);
        return false;
    }

    private void clearSignupPinFailures(AppUser user) {
        user.setSignupPinFailures(0);
        user.setSignupPinWindowStartedAt(null);
        user.setSignupPinBlockedUntil(null);
    }

    private static final class SignupPinMismatch extends BadRequestException {
        private SignupPinMismatch() { super("가입 시 설정한 2차 비밀번호가 올바르지 않습니다."); }
    }

    private static final class SignupPinLimited extends TooManyRequestsException {
        private SignupPinLimited() { super("2차 비밀번호 확인 횟수를 초과했습니다. 15분 후 다시 시도해 주세요.", 900); }
    }

    private EmailVerificationResendResponse acceptedResendResponse() {
        return new EmailVerificationResendResponse(
                true,
                "요청을 처리했습니다. 메일이 도착하지 않으면 잠시 후 다시 시도해 주세요."
        );
    }

    void ensureConfigured() {
        if (!enabled || tokenValidity == null || tokenValidity.isZero() || tokenValidity.isNegative()) {
            throw new ServiceUnavailableException("이메일 인증 기능이 아직 설정되지 않았습니다.");
        }
        validateBaseUrl();
    }

    String createRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hashToken(String rawToken) {
        if (!StringUtils.hasText(rawToken) || rawToken.length() > 256) {
            throw new BadRequestException("유효하지 않은 이메일 인증 링크입니다.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("이메일 인증 토큰을 처리할 수 없습니다.", exception);
        }
    }

    String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private void validateBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new ServiceUnavailableException("이메일 인증 페이지 주소가 설정되지 않았습니다.");
        }
        if (!AuthEndpointPolicy.isSafeUrl(baseUrl, false)) {
            throw new ServiceUnavailableException("이메일 인증 페이지 주소 설정이 올바르지 않습니다.");
        }
    }

    public String buildVerificationUrl(String rawToken) {
        validateBaseUrl();
        return baseUrl.trim().replaceAll("/+$", "") + "/#verify-email/" + rawToken;
    }

    String buildProfileVerificationUrl(String rawToken) {
        validateBaseUrl();
        return baseUrl.trim().replaceAll("/+$", "") + "/#verify-profile-email/" + rawToken;
    }

    String buildWithdrawalRecoveryUrl(String rawToken) {
        validateBaseUrl();
        return baseUrl.trim().replaceAll("/+$", "") + "/#withdraw-kakao/" + rawToken;
    }

    Duration validity() {
        return tokenValidity;
    }

    private record VerificationToken(String rawValue) {
    }
}

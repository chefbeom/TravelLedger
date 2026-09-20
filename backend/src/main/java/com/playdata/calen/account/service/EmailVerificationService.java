package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.EmailVerificationToken;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.dto.EmailVerificationResendRequest;
import com.playdata.calen.account.dto.EmailVerificationResendResponse;
import com.playdata.calen.account.dto.EmailVerificationResultResponse;
import com.playdata.calen.account.dto.EmailVerificationStartResponse;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import java.net.URI;
import java.net.URISyntaxException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailVerificationService {

    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final AppUserService appUserService;
    private final AccountSetupService accountSetupService;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailVerificationSender emailVerificationSender;
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
        VerificationToken token = issueToken(user);
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

    @Transactional
    public EmailVerificationResultResponse verify(String rawToken) {
        ensureConfigured();
        EmailVerificationToken token = tokenRepository.findForUpdateByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        LocalDateTime now = LocalDateTime.now();
        if (token.getUsedAt() != null || !now.isBefore(token.getExpiresAt())) {
            throw new BadRequestException("만료되었거나 이미 사용한 이메일 인증 링크입니다.");
        }

        AppUser user = token.getUser();
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
        Optional<AppUser> userOptional = appUserService.findUserByEmail(request.email());
        if (userOptional.isEmpty()) {
            return acceptedResendResponse();
        }
        AppUser user = userOptional.get();
        if (user.isActive() && user.isEmailVerified()) {
            return acceptedResendResponse();
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<EmailVerificationToken> latest = tokenRepository
                .findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (latest.isPresent()
                && Duration.between(latest.get().getCreatedAt(), now).compareTo(RESEND_COOLDOWN) < 0) {
            throw new TooManyRequestsException("인증 메일은 잠시 후 다시 요청해 주세요.");
        }

        VerificationToken token = issueToken(user);
        emailVerificationSender.send(
                user.getEmail(),
                buildVerificationUrl(token.rawValue()),
                tokenValidity
        );
        return acceptedResendResponse();
    }

    private VerificationToken issueToken(AppUser user) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateOpenTokens(user.getId(), now);
        String rawValue = createRawToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(hashToken(rawValue));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(tokenValidity));
        tokenRepository.save(token);
        return new VerificationToken(rawValue);
    }

    private EmailVerificationResendResponse acceptedResendResponse() {
        return new EmailVerificationResendResponse(
                true,
                "요청을 처리했습니다. 메일이 도착하지 않으면 잠시 후 다시 시도해 주세요."
        );
    }

    private void ensureConfigured() {
        if (!enabled || tokenValidity == null || tokenValidity.isZero() || tokenValidity.isNegative()) {
            throw new ServiceUnavailableException("이메일 인증 기능이 아직 설정되지 않았습니다.");
        }
        validateBaseUrl();
    }

    private String createRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
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

    private String maskEmail(String email) {
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
        try {
            URI uri = new URI(baseUrl.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new ServiceUnavailableException("이메일 인증 페이지 주소 설정이 올바르지 않습니다.");
            }
        } catch (URISyntaxException exception) {
            throw new ServiceUnavailableException("이메일 인증 페이지 주소 설정이 올바르지 않습니다.");
        }
    }

    public String buildVerificationUrl(String rawToken) {
        validateBaseUrl();
        return baseUrl.trim().replaceAll("/+$", "") + "/#verify-email/" + rawToken;
    }

    private record VerificationToken(String rawValue) {
    }
}

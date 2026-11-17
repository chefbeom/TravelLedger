package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AccountEmailVerification;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.EmailVerificationResultResponse;
import com.playdata.calen.account.dto.EmailVerificationStartResponse;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.WithdrawalChallengeRevoker;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountEmailVerificationService {
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private final AppUserRepository users;
    private final AppUserService appUsers;
    private final AccountEmailVerificationRepository tokens;
    private final EmailVerificationService verification;
    private final EmailVerificationSender sender;
    private final WithdrawalChallengeRevoker withdrawalChallengeRevoker;
    private final EntityManager entities;

    @Transactional
    public EmailVerificationStartResponse start(Long userId, String emailRaw, String secondaryPin) {
        verification.ensureConfigured();
        AppUser user = users.findActiveForPreferenceUpdate(userId)
                .orElseThrow(() -> new BadRequestException("사용자 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        appUsers.ensureSecondaryPinMatches(user, secondaryPin);
        String email = appUsers.normalizeEmailFormat(emailRaw);
        users.findByEmailIgnoreCase(email).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) throw new BadRequestException("이미 사용 중인 이메일입니다.");
            if (existing.isEmailVerified()) throw new BadRequestException("이미 인증된 이메일입니다.");
        });
        LocalDateTime now = LocalDateTime.now();
        tokens.findTopByUserIdOrderByCreatedAtDesc(userId).ifPresent(latest -> {
            if (Duration.between(latest.getCreatedAt(), now).compareTo(RESEND_COOLDOWN) < 0) {
                throw new TooManyRequestsException("인증 메일은 잠시 후 다시 요청해 주세요.");
            }
        });
        tokens.invalidateOpenTokens(userId, now);
        String raw = verification.createRawToken();
        AccountEmailVerification token = new AccountEmailVerification();
        token.setUser(user);
        token.setTargetEmail(email);
        token.setTokenHash(verification.hashToken(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(verification.validity()));
        tokens.save(token);
        sender.send(email, verification.buildProfileVerificationUrl(raw), verification.validity());
        return new EmailVerificationStartResponse(true, verification.maskEmail(email), verification.validity().toSeconds(),
                "인증 메일을 보냈습니다. 링크를 열면 이메일이 등록됩니다.");
    }

    @Transactional
    public EmailVerificationResultResponse verify(String raw) {
        verification.ensureConfigured();
        String hash = verification.hashToken(raw);
        Long userId = tokens.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        // Use the same user-then-token lock order as issuance to avoid a resend/verify deadlock.
        AppUser user = users.findActiveForPreferenceUpdate(userId)
                .orElseThrow(() -> new BadRequestException("사용할 수 없는 계정입니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        AccountEmailVerification token = tokens.findForUpdateByTokenHash(hash)
                .orElseThrow(() -> new BadRequestException("유효하지 않은 이메일 인증 링크입니다."));
        entities.refresh(token, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = LocalDateTime.now();
        if (token.getUsedAt() != null || !now.isBefore(token.getExpiresAt())) {
            throw new BadRequestException("만료되었거나 이미 사용한 이메일 인증 링크입니다.");
        }
        users.findByEmailIgnoreCase(token.getTargetEmail()).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) throw new BadRequestException("이미 사용 중인 이메일입니다.");
        });
        withdrawalChallengeRevoker.revoke(userId, now);
        user.setEmail(token.getTargetEmail());
        user.setEmailVerified(true);
        try {
            // The unique database constraint is the final guard if two accounts verify the same address concurrently.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("이메일 인증을 완료할 수 없습니다. 이메일 주소를 확인해 주세요.");
        }
        token.setUsedAt(now);
        tokens.invalidateOpenTokens(userId, now);
        return new EmailVerificationResultResponse(true, "이메일 인증이 완료되었습니다.");
    }
}

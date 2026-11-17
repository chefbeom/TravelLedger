package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.withdrawal.AuditHistorySanitizer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Releases identifiers only for explicitly tracked, still-unverified new registrations. */
@Service
@RequiredArgsConstructor
public class EmailSignupExpiryService {
    public static final Duration RETENTION = Duration.ofDays(7);

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final SocialAccountRepository socialAccounts;
    private final AuditHistorySanitizer auditHistory;
    private final EntityManager entities;

    @Transactional
    public boolean expireIfEligible(Long userId, LocalDateTime now) {
        var user = users.lockForAccountSecurityUpdate(userId).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user == null || user.isActive() || user.isEmailVerified() || user.isExpiredEmailSignup()
                || user.getRole() != AppUserRole.USER || socialAccounts.existsByUserId(userId)) return false;

        // User first, token second: same order as resend, confirmation and administrative cancellation.
        var token = tokens.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(userId).orElse(null);
        if (token != null) entities.refresh(token, LockModeType.PESSIMISTIC_WRITE);
        if (token == null || !token.isCleanupEligible()
                || token.getCreatedAt().isAfter(now.minus(RETENTION))) return false;

        auditHistory.anonymize(userId, user.getLoginId());
        tokens.deleteAllForUser(userId);
        // Retain a non-reactivatable, anonymous row for any existing foreign keys.
        user.setLoginId("expired_" + UUID.randomUUID().toString().replace("-", ""));
        user.setDisplayName("만료된 가입");
        user.setEmail(null);
        user.setPasswordHash(null);
        user.setSecondaryPinHash(null);
        user.setSignupPinFailures(0);
        user.setSignupPinWindowStartedAt(null);
        user.setSignupPinBlockedUntil(null);
        user.setHouseholdAggregateSettingsJson(null);
        user.setExpiredEmailSignup(true);
        users.saveAndFlush(user);
        return true;
    }
}

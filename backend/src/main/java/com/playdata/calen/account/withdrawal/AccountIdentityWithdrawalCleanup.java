package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes credentials and obsolete proof data after withdrawal has been durably prepared. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class AccountIdentityWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeCredentialsAndProofs(Long userId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }

        // Profile verification records contain previous target addresses, not just token hashes.
        // Verification and recovery acquire the user lock first, so no new proof can be issued
        // after the account is disabled and this transaction commits.
        for (String query : java.util.List.of(
                "delete from AccountEmailVerification proof where proof.user.id = :userId",
                "delete from EmailVerificationToken proof where proof.user.id = :userId",
                "delete from WithdrawalEmailChallenge proof where proof.user.id = :userId")) {
            entities.createQuery(query).setParameter("userId", userId).executeUpdate();
        }
        entities.createQuery("update SocialAccount social set social.email = null where social.user.id = :userId")
                .setParameter("userId", userId).executeUpdate();

        user.setDisplayName("탈퇴 처리 중");
        user.setPasswordHash(null);
        user.setSecondaryPinHash(null);
        user.setHouseholdAggregateSettingsJson(null);
        user.setSignupPinFailures(0);
        user.setSignupPinWindowStartedAt(null);
        user.setSignupPinBlockedUntil(null);
        // The original login ID/email still reserve those identifiers until safe finalization.
        // The Kakao subject must also remain for unlink and re-connection protection.
        // These retained values are personal data; this step is not a completed deletion.
    }
}

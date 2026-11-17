package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serialize invite use with withdrawal without changing ordinary admin deactivation semantics. */
@Service
@RequiredArgsConstructor
public class InviteWithdrawalFence {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public AppUser lockAvailableCreator(Long userId) {
        if (userId == null || userId <= 0) return null;
        var user = users.lockForAccountSecurityUpdate(userId).orElse(null);
        if (user == null) return null;
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isActive() && withdrawals.hasPendingWithdrawalForUpdate(userId)) return null;
        return user;
    }
}

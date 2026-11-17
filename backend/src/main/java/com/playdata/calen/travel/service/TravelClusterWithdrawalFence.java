package com.playdata.calen.travel.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Orders cluster writes after the account lock taken by withdrawal. */
@Service
@RequiredArgsConstructor
public class TravelClusterWithdrawalFence {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAvailableOwner(Long userId) {
        if (userId == null || userId <= 0) return false;
        var user = users.lockForAccountSecurityUpdate(userId).orElse(null);
        if (user == null) return false;
        // A request-scoped persistence context may still hold the pre-withdrawal user.
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        // Ordinary administrator deactivation must retain legacy background refreshes.
        return user.isActive() || !withdrawals.hasPendingWithdrawalForUpdate(userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailableOwner(Long userId) {
        if (!lockAvailableOwner(userId)) throw new NotFoundException("사용자 계정을 찾을 수 없습니다.");
    }
}

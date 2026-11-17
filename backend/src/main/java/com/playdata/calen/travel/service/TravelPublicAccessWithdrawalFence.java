package com.playdata.calen.travel.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public readers share a lock; withdrawal takes the conflicting account write lock. */
@Service
@RequiredArgsConstructor
public class TravelPublicAccessWithdrawalFence {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailableOwner(Long owner) {
        // A surrounding read-only transaction may prohibit locking reads on MySQL-compatible DBs.
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new ServiceUnavailableException("공유 지도를 안전하게 확인할 수 없습니다.");
        }
        if (owner == null || owner <= 0) throw new NotFoundException("Shared travel map not found.");
        var user = users.lockForAccountSecurityRead(owner)
                .orElseThrow(() -> new NotFoundException("Shared travel map not found."));
        entities.refresh(user, LockModeType.PESSIMISTIC_READ);
        // Existing administrator-disabled users retain their previous public-map behavior.
        if (!user.isActive() && withdrawals.hasPendingWithdrawalForUpdate(owner)) {
            throw new NotFoundException("Shared travel map not found.");
        }
    }
}

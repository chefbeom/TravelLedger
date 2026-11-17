package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes a public link read with withdrawal without changing legacy disabled-account behavior. */
@Service
@RequiredArgsConstructor
public class PublicShareAccessGuard {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAvailableOwner(Long ownerId) {
        var owner = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new BadRequestException("Download link is expired or no longer available."));
        entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        if (!owner.isActive() && withdrawals.hasPendingWithdrawalForUpdate(ownerId)) {
            throw new BadRequestException("Download link is expired or no longer available.");
        }
    }
}

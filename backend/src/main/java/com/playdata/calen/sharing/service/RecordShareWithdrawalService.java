package com.playdata.calen.sharing.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One withdrawal step only; this is not a public deletion endpoint or a complete cleanup. */
@Service
@RequiredArgsConstructor
public class RecordShareWithdrawalService {
    private final AppUserRepository users;
    private final RecordShareRepository shares;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;
    private final TravelPlanWithdrawalService travelPlans;

    /** Caller must first stop new work, deactivate the account and hold the surrounding transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int removeEnvelopesForInactiveUser(Long userId) {
        if (userId == null) throw new IllegalArgumentException("User is required.");
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 공유 기록을 정리할 수 있습니다.");
        }
        travelPlans.requireNoOwnedSharedPlansRemain(userId);
        // Accepted copies belong to the recipient and are not connected by a foreign key.
        // Bulk deletion does not remove or modify those copies, or unrelated users' shares.
        return shares.deleteEnvelopesForWithdrawal(userId);
    }
}

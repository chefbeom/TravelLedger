package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes new shares and shared-file access with either participant's withdrawal. */
@Service
@RequiredArgsConstructor
public class DriveShareParticipantFence {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCreation(Long ownerId, Long recipientId) {
        Map<Long, AppUser> participants = lockParticipants(ownerId, recipientId);
        if (!participants.get(ownerId).isActive()) throw new NotFoundException("사용자 정보를 찾지 못했습니다.");
        if (!participants.get(recipientId).isActive()) throw new NotFoundException("공유할 사용자를 찾지 못했습니다.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwnerForWrite(Long ownerId) {
        if (ownerId == null || ownerId <= 0) throw new NotFoundException("사용자 정보를 찾지 못했습니다.");
        AppUser owner = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자 정보를 찾지 못했습니다."));
        entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        if (!owner.isActive()) throw new NotFoundException("사용자 정보를 찾지 못했습니다.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForAccess(Long ownerId, Long recipientId) {
        Map<Long, AppUser> participants = lockParticipants(ownerId, recipientId);
        AppUser recipient = participants.get(recipientId);
        AppUser owner = participants.get(ownerId);
        if (!recipient.isActive() || (!owner.isActive() && withdrawals.hasPendingWithdrawalForUpdate(ownerId))) {
            throw new NotFoundException("Shared file not found.");
        }
    }

    private Map<Long, AppUser> lockParticipants(Long ownerId, Long recipientId) {
        if (ownerId == null || ownerId <= 0 || recipientId == null || recipientId <= 0) {
            throw new NotFoundException("Shared file not found.");
        }
        TreeSet<Long> orderedIds = new TreeSet<>();
        orderedIds.add(ownerId);
        orderedIds.add(recipientId);
        Map<Long, AppUser> participants = new TreeMap<>();
        for (Long id : orderedIds) {
            AppUser user = users.lockForAccountSecurityUpdate(id)
                    .orElseThrow(() -> new NotFoundException("Shared file not found."));
            entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            participants.put(id, user);
        }
        return participants;
    }
}

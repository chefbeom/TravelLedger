package com.playdata.calen.travel.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.domain.model.TravelSharingData.Recipient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes new travel shares and group membership with participant withdrawal. */
@Service
@RequiredArgsConstructor
public class TravelSharingParticipantFence {
    private final AppUserRepository users;
    private final PendingWithdrawalGuard withdrawals;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForPlanShare(Long ownerId, Long recipientId, String recipientLogin) {
        Map<Long, AppUser> locked = lock(ownerId, List.of(recipientId));
        requireActiveOwner(locked, ownerId);
        AppUser recipient = locked.get(recipientId);
        if (!recipient.isActive() || !Objects.equals(recipient.getLoginId(), recipientLogin)) {
            throw new NotFoundException("공유할 사용자를 찾을 수 없습니다.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppUser lockForGroup(Long ownerId, List<Recipient> recipients) {
        Map<Long, AppUser> locked = lock(ownerId, recipients.stream().map(Recipient::id).toList());
        requireActiveOwner(locked, ownerId);
        for (Recipient recipient : recipients) {
            AppUser current = locked.get(recipient.id());
            if (!current.isActive() || !Objects.equals(current.getLoginId(), recipient.loginId())) {
                throw new NotFoundException("공유 그룹 사용자를 찾을 수 없습니다: " + recipient.loginId());
            }
        }
        return locked.get(ownerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForAccess(Long ownerId, Long recipientId) {
        Map<Long, AppUser> locked = lock(ownerId, List.of(recipientId));
        AppUser owner = locked.get(ownerId);
        if (!locked.get(recipientId).isActive()
                || (!owner.isActive() && withdrawals.hasPendingWithdrawalForUpdate(ownerId))) {
            throw new NotFoundException("공유된 여행 전시를 찾을 수 없습니다.");
        }
    }

    private Map<Long, AppUser> lock(Long ownerId, List<Long> recipients) {
        if (ownerId == null || ownerId <= 0 || recipients.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new NotFoundException("사용자 계정을 찾을 수 없습니다.");
        }
        var ids = new TreeSet<Long>(recipients);
        ids.add(ownerId);
        Map<Long, AppUser> locked = new HashMap<>();
        for (Long id : ids) {
            AppUser account = users.lockForAccountSecurityUpdate(id)
                    .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
            entities.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            locked.put(id, account);
        }
        return locked;
    }

    private void requireActiveOwner(Map<Long, AppUser> locked, Long ownerId) {
        if (!locked.get(ownerId).isActive()) throw new NotFoundException("사용자 계정을 찾을 수 없습니다.");
    }
}

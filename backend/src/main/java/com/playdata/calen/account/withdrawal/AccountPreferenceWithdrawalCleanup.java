package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal, owner-scoped step; never enables or represents a completed withdrawal by itself. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class AccountPreferenceWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removePreferencesAndGoals(Long userId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        entities.createQuery("delete from UserLayoutSetting setting where setting.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from CalendarEvent event where event.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from CalendarMapGrant mapping where mapping.viewer.id = :userId or mapping.shareOwnerId = :userId")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from HouseholdGoal goal where goal.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
        user.setHouseholdAggregateSettingsJson(null);
    }
}

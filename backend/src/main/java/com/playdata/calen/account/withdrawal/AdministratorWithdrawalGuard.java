package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Locks current administrators in stable order before locking an administrator who is leaving. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class AdministratorWithdrawalGuard {
    private final AppUserRepository users;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndCheckIfAdministrator(Long targetId) {
        if (!users.existsByIdAndRoleAndActiveTrue(targetId, AppUserRole.ADMIN)) return false;
        int otherActiveAdministrators = 0;
        for (Long adminId : users.findActiveUserIdsByRole(AppUserRole.ADMIN)) {
            AppUser admin = users.lockForAccountSecurityUpdate(adminId).orElse(null);
            if (admin != null) entities.refresh(admin, LockModeType.PESSIMISTIC_WRITE);
            if (admin != null && admin.isActive() && admin.getRole() == AppUserRole.ADMIN
                    && !adminId.equals(targetId)) otherActiveAdministrators++;
        }
        if (otherActiveAdministrators == 0) {
            throw new BadRequestException("마지막 관리자는 탈퇴할 수 없습니다. 먼저 다른 활성 관리자를 지정해 주세요.");
        }
        return true;
    }

    public void requireProtectedIfAdministrator(AppUser user, boolean prepared) {
        if (user.getRole() == AppUserRole.ADMIN && !prepared) {
            throw new BadRequestException("관리자 상태가 변경되었습니다. 다시 시도해 주세요.");
        }
    }
}

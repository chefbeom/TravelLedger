package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Revokes the departing owner's DB-backed public surfaces, without deleting shared objects. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class PublicShareWithdrawalSuspender {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;
    private final TravelSummaryInvalidationPort summaries;

    @Transactional(propagation = Propagation.MANDATORY)
    public void suspend(Long userId, LocalDateTime now) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        if (now == null) throw new BadRequestException("공유 중단 시간을 확인할 수 없습니다.");
        entities.createQuery("update DriveDownloadLink link set link.revokedAt = :now "
                        + "where link.owner.id = :owner and link.revokedAt is null")
                .setParameter("now", now).setParameter("owner", userId).executeUpdate();
        entities.createQuery("update RecordMapShare share set share.active = false "
                        + "where share.ownerId = :owner and share.active = true")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("update TravelMapShareLink link set link.active = false, link.updatedAt = :now "
                        + "where link.owner.id = :owner and link.active = true")
                .setParameter("now", now).setParameter("owner", userId).executeUpdate();
        entities.createQuery("update TravelExpenseRecord record set record.sharedWithCommunity = false "
                        + "where record.plan.id in (select plan.id from TravelPlan plan where plan.owner.id = :owner) "
                        + "and record.sharedWithCommunity = true")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("update TravelPlan plan set plan.publicShared = false, plan.publicSharedAt = null "
                        + "where plan.owner.id = :owner and plan.publicShared = true")
                .setParameter("owner", userId).executeUpdate();
        // This invalidates again after commit, but Redis availability is still a deployment prerequisite.
        summaries.invalidateOwner(userId);
    }
}

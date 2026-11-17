package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.out.TravelClusterSnapshotCachePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes only the departing user's derived map data and queued refresh state. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelClusterWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final TravelClusterSnapshotCachePort snapshots;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeDerivedData(Long userId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        // Refresh writers lock the same account before touching the job row. A
        // precomputed result therefore cannot be published after this transaction.
        entities.createQuery("delete from TravelPhotoClusterMember member where member.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from TravelPhotoCluster cluster where cluster.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from TravelPhotoClusterRefreshJob job where job.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        snapshots.invalidateNow(userId);
        snapshots.invalidateAfterCommit(userId);
    }
}

package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.service.DriveDownloadLinkAccessLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Anonymizes access logs for the departing owner's files without touching other owners' logs. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class DriveAccessLogWithdrawalCleanup {
    private static final List<String> SAFE_STATUSES = List.of("success", "invalid", "revoked", "expired", "limit_reached",
            "invalid_item", "trashed", "shared_success", "shared_not_found", "shared_unavailable", "shared_permission_denied");
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void anonymize(Long owner, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(owner)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(owner).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        entities.createQuery("""
                update DriveDownloadLinkAccessLog log
                set log.ownerId = null, log.itemId = null, log.linkId = null,
                    log.clientAddress = null, log.userAgent = null, log.tokenFingerprint = :anonymous,
                    log.status = case when log.status in :safeStatuses then log.status else 'unknown' end
                where log.ownerId = :owner
                """)
                .setParameter("anonymous", DriveDownloadLinkAccessLogService.ANONYMOUS_DIRECT_SHARE_FINGERPRINT)
                .setParameter("safeStatuses", SAFE_STATUSES).setParameter("owner", owner).executeUpdate();
    }
}

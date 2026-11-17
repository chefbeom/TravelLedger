package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Requeues only known transient failures. It never performs or declares an external deletion. */
@Service
@RequiredArgsConstructor
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class IntegrationWithdrawalRetryService {
    private static final Map<WithdrawalCleanupKind, Set<String>> RETRYABLE_FAILURES = Map.of(
            WithdrawalCleanupKind.KAKAO_UNLINK, Set.of("KAKAO_UNLINK_UNCONFIRMED"),
            WithdrawalCleanupKind.LEDGER_IMAGE, Set.of("IMAGE_STORAGE_UNCONFIRMED", "IMAGE_STORAGE_ID_MISMATCH",
                    "IMAGE_STORAGE_MARKER_UNCONFIRMED"),
            WithdrawalCleanupKind.SUPPORT_ATTACHMENT, Set.of("SUPPORT_FILE_IO_UNCONFIRMED",
                    "SUPPORT_STORAGE_MISMATCH", "SUPPORT_STORAGE_UNSUPPORTED"));

    private final AppUserRepository users;
    private final AppUserService appUsers;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskRepository tasks;
    private final LoginAuditLogService audit;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    public static boolean canRetry(WithdrawalCleanupTask task) {
        return task != null && task.getKind() != null && task.getState() == WithdrawalCleanupTask.State.FAILED
                && task.getEncryptedPayload() != null && task.getLastErrorCode() != null
                && RETRYABLE_FAILURES.getOrDefault(task.getKind(), Set.of()).contains(task.getLastErrorCode());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retry(Long adminId, UUID requestId, UUID taskId, String pin, String clientIp, String userAgent) {
        if (adminId == null || adminId <= 0 || requestId == null || taskId == null) throw unavailable();
        var admin = users.lockForAccountSecurityUpdate(adminId)
                .orElseThrow(() -> new AccessDeniedException("관리자 계정을 확인할 수 없습니다."));
        entities.refresh(admin, LockModeType.PESSIMISTIC_WRITE);
        if (!admin.isActive() || admin.getRole() != AppUserRole.ADMIN) throw new AccessDeniedException("관리자 권한이 필요합니다.");
        appUsers.ensureSecondaryPinMatches(admin, pin);
        var request = requests.findById(requestId.toString()).orElseThrow(this::unavailable);
        var owner = request.getUserId() == null ? null : users.lockForAccountSecurityUpdate(request.getUserId()).orElse(null);
        if (owner != null) entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        entities.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        var task = tasks.lockById(taskId.toString()).orElseThrow(this::unavailable);
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        if (owner == null || owner.isActive() || !owner.getId().equals(request.getUserId())
                || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || !requestId.toString().equals(task.getRequestId()) || !canRetry(task)) throw unavailable();
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw unavailable();
        task.setState(WithdrawalCleanupTask.State.PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(now);
        task.setClaimToken(null);
        task.setLeaseUntil(null);
        task.setLastErrorCode(null);
        task.setUpdatedAt(now);
        tasks.saveAndFlush(task);
        String safeIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        if (safeIp.length() > 120) safeIp = safeIp.substring(0, 120);
        audit.record(admin.getLoginId(), safeIp, userAgent, LoginAuditStatus.ADMIN_ACTION,
                "WITHDRAWAL_TASK_RETRY", admin);
    }

    private BadRequestException unavailable() {
        return new BadRequestException("다시 시도할 수 없는 작업입니다. 상태와 정리 대상을 확인해 주세요.");
    }
}

package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The task row remains locked across the exact-file operation, fencing stale workers. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class SupportAttachmentCleanupHandler {
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskRepository tasks;
    private final AppUserRepository users;
    private final SupportInquiryRepository inquiries;
    private final WithdrawalPayloadCipher cipher;
    private final SecureSupportAttachmentFiles files;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result handle(WithdrawalCleanupTaskStore.Claim claim) {
        if (claim == null || claim.requestId() == null || claim.taskId() == null || claim.token() == null) return Result.LOST_LEASE;
        var request = requests.findById(claim.requestId().toString()).orElse(null);
        var user = request == null || request.getUserId() == null ? null
                : users.lockForAccountSecurityUpdate(request.getUserId()).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var task = tasks.lockById(claim.taskId().toString()).orElse(null);
        if (task == null) return Result.LOST_LEASE;
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = databaseNow();
        if (task.getKind() != WithdrawalCleanupKind.SUPPORT_ATTACHMENT
                || !claim.requestId().toString().equals(task.getRequestId())
                || task.getState() != WithdrawalCleanupTask.State.PROCESSING
                || !claim.token().toString().equals(task.getClaimToken())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) return Result.LOST_LEASE;
        if (request != null) entities.refresh(request);
        if (request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || user == null || user.isActive() || !user.getId().equals(request.getUserId())) {
            return fail(task, now, false, "SUPPORT_ACCOUNT_STATE_INVALID");
        }
        SupportAttachmentTarget target;
        try {
            String payload = cipher.decrypt(claim.requestId(), claim.taskId(), task.getKind().name(), task.getEncryptedPayload());
            target = json.readValue(payload, SupportAttachmentTarget.class);
        } catch (Exception invalid) {
            return fail(task, now, false, "SUPPORT_PROVENANCE_MISSING");
        }
        if (target == null || !target.valid() || !user.getId().equals(target.ownerId())) return fail(task, now, false, "SUPPORT_PROVENANCE_INVALID");
        if (inquiries.existsByAttachmentStoragePath(target.path())) return fail(task, now, false, "SUPPORT_STILL_REFERENCED");
        var result = files.deleteExact(target);
        now = databaseNow();
        return switch (result) {
            case DELETED -> succeed(task, now);
            case MISSING -> fail(task, now, false, "SUPPORT_FILE_MISSING");
            case MANUAL_REVIEW -> fail(task, now, false, "SUPPORT_FILE_UNSAFE");
            case WRONG_STORAGE -> fail(task, now, true, "SUPPORT_STORAGE_MISMATCH");
            case UNSUPPORTED -> fail(task, now, true, "SUPPORT_STORAGE_UNSUPPORTED");
            case RETRY -> fail(task, now, task.getAttempts() < 8, "SUPPORT_FILE_IO_UNCONFIRMED");
        };
    }

    private Result succeed(WithdrawalCleanupTask task, LocalDateTime now) {
        task.setState(WithdrawalCleanupTask.State.SUCCEEDED);
        task.setEncryptedPayload(null); task.setClaimToken(null); task.setLeaseUntil(null);
        task.setLastErrorCode(null); task.setUpdatedAt(now);
        return Result.SUCCEEDED;
    }

    private Result fail(WithdrawalCleanupTask task, LocalDateTime now, boolean retry, String code) {
        task.setState(retry ? WithdrawalCleanupTask.State.PENDING : WithdrawalCleanupTask.State.FAILED);
        task.setNextAttemptAt(now.plusMinutes(5)); task.setClaimToken(null); task.setLeaseUntil(null);
        task.setLastErrorCode(code); task.setUpdatedAt(now);
        return retry ? Result.RETRY_PENDING : Result.MANUAL_REVIEW;
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new IllegalStateException("Withdrawal database time unavailable.");
        return now;
    }

    public enum Result { SUCCEEDED, RETRY_PENDING, MANUAL_REVIEW, LOST_LEASE }
}

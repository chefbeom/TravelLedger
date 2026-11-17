package com.playdata.calen.account.withdrawal;

import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class SupportAttachmentCleanupExecutor {
    private final WithdrawalCleanupTaskStore tasks;
    private final SupportAttachmentCleanupHandler handler;

    public Result processNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Cleanup must start outside a transaction.");
        var claim = tasks.claimNext(Set.of(WithdrawalCleanupKind.SUPPORT_ATTACHMENT), Duration.ofMinutes(5));
        if (claim.isEmpty()) return Result.NO_TASK;
        return switch (handler.handle(claim.get())) {
            case SUCCEEDED -> Result.SUCCEEDED;
            case RETRY_PENDING -> Result.RETRY_PENDING;
            case MANUAL_REVIEW -> Result.MANUAL_REVIEW;
            case LOST_LEASE -> Result.LOST_LEASE;
        };
    }

    public enum Result { NO_TASK, SUCCEEDED, RETRY_PENDING, MANUAL_REVIEW, LOST_LEASE }
}

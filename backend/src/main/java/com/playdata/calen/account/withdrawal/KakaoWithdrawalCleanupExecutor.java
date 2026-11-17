package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.common.exception.BadRequestException;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded external operation per call. Public withdrawal is not yet enabled. */
@Service
@RequiredArgsConstructor
public class KakaoWithdrawalCleanupExecutor {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_NETWORK_BUDGET = Duration.ofMinutes(3);
    private static final int MAX_ATTEMPTS = 8;
    private final WithdrawalCleanupTaskStore tasks;
    private final KakaoOAuthClient kakao;
    private final KakaoOAuthProperties properties;
    private final KakaoWithdrawalPreflight preflight;

    public Result processNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Withdrawal provider calls must run outside a transaction.");
        }
        if (!kakao.isUnlinkConfigured() || !networkBudgetValid()) return Result.CONFIGURATION_REQUIRED;
        var candidate = tasks.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), LEASE);
        if (candidate.isEmpty()) return Result.NO_TASK;
        var claim = candidate.get();
        var checked = preflight.verify(claim, MAX_NETWORK_BUDGET.plusSeconds(10));
        if (checked == KakaoWithdrawalPreflight.Result.LOST_LEASE) return Result.LOST_LEASE;
        if (checked != KakaoWithdrawalPreflight.Result.READY) return fail(claim, false, "KAKAO_ACCOUNT_STATE_INVALID");
        try {
            kakao.unlinkAccount(claim.payload());
        } catch (BadRequestException invalidTarget) {
            return fail(claim, false, "KAKAO_TARGET_INVALID");
        } catch (RuntimeException unconfirmed) {
            // A timeout may mean Kakao processed the unlink but the response was lost. Never
            // infer success from an error; after bounded retries it requires reconciliation.
            return fail(claim, claim.attempt() < MAX_ATTEMPTS, "KAKAO_UNLINK_UNCONFIRMED");
        }
        // If the DB is unavailable, leave the claim for lease recovery rather than falsely
        // recording a provider failure after a confirmed unlink.
        return tasks.succeed(claim.taskId(), claim.token()) ? Result.SUCCEEDED : Result.LOST_LEASE;
    }

    private Result fail(WithdrawalCleanupTaskStore.Claim claim, boolean retryable, String code) {
        long delaySeconds = Math.min(30L << Math.min(Math.max(claim.attempt() - 1, 0), 6), 1800L);
        boolean recorded = tasks.fail(claim.taskId(), claim.token(), retryable, Duration.ofSeconds(delaySeconds), code);
        if (!recorded) return Result.LOST_LEASE;
        return retryable ? Result.RETRY_PENDING : Result.MANUAL_REVIEW;
    }

    private boolean networkBudgetValid() {
        Duration connect = properties.getConnectTimeout() == null ? Duration.ofSeconds(5) : properties.getConnectTimeout();
        Duration read = properties.getReadTimeout() == null ? Duration.ofSeconds(10) : properties.getReadTimeout();
        if (connect.isNegative() || connect.isZero() || read.isNegative() || read.isZero()
                || connect.compareTo(MAX_NETWORK_BUDGET) > 0 || read.compareTo(MAX_NETWORK_BUDGET) > 0) return false;
        return connect.plus(read).compareTo(MAX_NETWORK_BUDGET) <= 0;
    }

    public enum Result { CONFIGURATION_REQUIRED, NO_TASK, SUCCEEDED, RETRY_PENDING, MANUAL_REVIEW, LOST_LEASE }
}

package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal invocation only: no public endpoint or scheduled production worker. */
@Service
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerImageCleanupExecutor {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration REQUIRED_TIME = Duration.ofMinutes(3).plusSeconds(10);
    private static final int MAX_ATTEMPTS = 8;
    private final WithdrawalCleanupTaskStore tasks;
    private final LedgerImageCleanupPreflight preflight;
    private final LedgerImageVersionedObjectCleanup storage;
    private final ObjectProvider<MinioClient> clients;
    private final ObjectMapper json;
    private final String storageId;

    public LedgerImageCleanupExecutor(WithdrawalCleanupTaskStore tasks, LedgerImageCleanupPreflight preflight,
            LedgerImageVersionedObjectCleanup storage, ObjectProvider<MinioClient> clients, ObjectMapper json,
            @Value("${app.ledger.ocr.image-storage-id:}") String storageId) {
        this.tasks = tasks; this.preflight = preflight; this.storage = storage; this.clients = clients; this.json = json;
        this.storageId = storageId == null ? "" : storageId.trim();
    }

    public Result processNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object-storage cleanup must start outside a transaction.");
        }
        MinioClient client = clients.getIfAvailable();
        if (client == null || !LedgerImageStorageMarker.validId(storageId)) return Result.CONFIGURATION_REQUIRED;
        var candidate = tasks.claimNext(Set.of(WithdrawalCleanupKind.LEDGER_IMAGE), LEASE);
        if (candidate.isEmpty()) return Result.NO_TASK;
        var claim = candidate.get();
        var checked = preflight.verify(claim, REQUIRED_TIME);
        if (checked == LedgerImageCleanupPreflight.Result.LOST_LEASE) return Result.LOST_LEASE;
        if (checked != LedgerImageCleanupPreflight.Result.READY) return fail(claim, false, "IMAGE_ACCOUNT_OR_REFERENCE_INVALID");
        ImageTask target;
        try {
            target = json.readValue(claim.payload(), ImageTask.class);
        } catch (Exception invalid) {
            return fail(claim, false, "IMAGE_PROVENANCE_INVALID");
        }
        if (target == null || !storageId.equals(target.storageId())) {
            // Another storage deployment may own this task. Do not contact the wrong endpoint.
            return fail(claim, claim.attempt() < MAX_ATTEMPTS, "IMAGE_STORAGE_ID_MISMATCH");
        }
        AtomicReference<LedgerImageCleanupPreflight.Result> fence = new AtomicReference<>();
        LedgerImageVersionedObjectCleanup.Outcome outcome;
        try {
            outcome = storage.removeRecordedVersion(client, storageId, target, () -> {
                var current = preflight.verify(claim, REQUIRED_TIME);
                fence.set(current);
                return current == LedgerImageCleanupPreflight.Result.READY;
            });
        } catch (RuntimeException unconfirmed) {
            return fail(claim, claim.attempt() < MAX_ATTEMPTS, "IMAGE_STORAGE_UNCONFIRMED");
        }
        return switch (outcome) {
            case DELETED -> tasks.succeed(claim.taskId(), claim.token()) ? Result.SUCCEEDED : Result.LOST_LEASE;
            case MARKER_UNCONFIRMED -> fail(claim, claim.attempt() < MAX_ATTEMPTS, "IMAGE_STORAGE_MARKER_UNCONFIRMED");
            case MANUAL_REVIEW -> fail(claim, false, "IMAGE_VERSION_UNCONFIRMED");
            case FENCE_REJECTED -> fence.get() == LedgerImageCleanupPreflight.Result.LOST_LEASE
                    ? Result.LOST_LEASE : fail(claim, false, "IMAGE_ACCOUNT_OR_REFERENCE_INVALID");
        };
    }

    private Result fail(WithdrawalCleanupTaskStore.Claim claim, boolean retry, String code) {
        long delaySeconds = Math.min(30L << Math.min(Math.max(claim.attempt() - 1, 0), 6), 1800L);
        if (!tasks.fail(claim.taskId(), claim.token(), retry, Duration.ofSeconds(delaySeconds), code)) return Result.LOST_LEASE;
        return retry ? Result.RETRY_PENDING : Result.MANUAL_REVIEW;
    }

    public enum Result { NO_TASK, CONFIGURATION_REQUIRED, SUCCEEDED, RETRY_PENDING, MANUAL_REVIEW, LOST_LEASE }
}

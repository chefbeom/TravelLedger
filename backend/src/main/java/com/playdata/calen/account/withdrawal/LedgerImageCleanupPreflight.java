package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checks the task and known references twice; never calls object storage. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerImageCleanupPreflight {
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskRepository tasks;
    private final AppUserRepository users;
    private final WithdrawalPayloadCipher cipher;
    private final ObjectMapper json;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result verify(WithdrawalCleanupTaskStore.Claim claim, Duration requiredTime) {
        if (claim == null || claim.requestId() == null || claim.taskId() == null || claim.token() == null
                || claim.kind() != WithdrawalCleanupKind.LEDGER_IMAGE || requiredTime == null
                || requiredTime.isNegative() || requiredTime.isZero() || requiredTime.compareTo(Duration.ofMinutes(4)) > 0) {
            return Result.LOST_LEASE;
        }
        var request = requests.findById(claim.requestId().toString()).orElse(null);
        var user = request == null || request.getUserId() == null ? null
                : users.lockForAccountSecurityUpdate(request.getUserId()).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (request != null) entities.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        var task = tasks.lockById(claim.taskId().toString()).orElse(null);
        if (task == null) return Result.LOST_LEASE;
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new IllegalStateException("Withdrawal database time unavailable.");
        if (task.getKind() != WithdrawalCleanupKind.LEDGER_IMAGE
                || !claim.requestId().toString().equals(task.getRequestId())
                || task.getState() != WithdrawalCleanupTask.State.PROCESSING
                || !claim.token().toString().equals(task.getClaimToken())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now.plus(requiredTime))) return Result.LOST_LEASE;
        if (request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || user == null || user.isActive() || !user.getId().equals(request.getUserId())) return Result.MANUAL_REVIEW;
        ImageTask target;
        try {
            String payload = cipher.decrypt(claim.requestId(), claim.taskId(), task.getKind().name(), task.getEncryptedPayload());
            if (payload == null || claim.payload() == null || !MessageDigest.isEqual(
                    payload.getBytes(StandardCharsets.UTF_8), claim.payload().getBytes(StandardCharsets.UTF_8))) return Result.MANUAL_REVIEW;
            target = json.readValue(payload, ImageTask.class);
        } catch (Exception invalid) {
            return Result.MANUAL_REVIEW;
        }
        if (target == null || !target.validForVersionCleanup() || !user.getId().equals(target.ownerId())) return Result.MANUAL_REVIEW;
        return hasKnownReference(target.objectKey()) ? Result.MANUAL_REVIEW : Result.READY;
    }

    private boolean hasKnownReference(String key) {
        for (String query : List.of(
                "select count(image) from LedgerImageAnalysisRequest image where image.imageObjectKey = :key",
                "select count(photo) from PlacePhoto photo where photo.storagePath = :key",
                "select count(asset) from TravelMediaAsset asset where asset.storagePath = :key",
                "select count(asset) from FamilyMediaAsset asset where asset.storagePath = :key",
                "select count(item) from DriveItem item where item.storagePath = :key",
                "select count(version) from DriveItemVersion version where version.storagePath = :key",
                "select count(profile) from DriveProfileSettings profile where profile.profileImagePath = :key",
                "select count(inquiry) from SupportInquiry inquiry where inquiry.attachmentStoragePath = :key",
                "select count(g) from DriveUploadGrant g where g.objectKey = :key",
                "select count(g) from FamilyMediaUploadGrant g where g.objectKey = :key",
                "select count(g) from TravelUploadGrant g where g.objectKey = :key or g.originalKey = :key")) {
            if (entities.createQuery(query, Long.class).setParameter("key", key).getSingleResult() != 0L) return true;
        }
        // GPX files are stored as JSON. The validated key contains no LIKE wildcards.
        return entities.createQuery("select count(route) from TravelRouteSegment route where route.gpxFilesJson like :pattern", Long.class)
                .setParameter("pattern", "%" + key + "%").getSingleResult() != 0L;
    }

    public enum Result { READY, MANUAL_REVIEW, LOST_LEASE }
}

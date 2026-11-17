package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.drive.domain.DriveItemType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inventory only. Source rows and objects remain intact and no storage operation is authorized. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class DriveWithdrawalInventory {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public InventorySummary capture(Long ownerId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(ownerId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        requireConsistentOwners(ownerId);
        boolean invalidItemPath = captureItems(ownerId, requestId);
        boolean invalidVersionPath = captureVersions(ownerId, requestId);
        captureUploadGrants(ownerId, requestId);
        return new InventorySummary(invalidItemPath || invalidVersionPath);
    }

    private void requireConsistentOwners(Long ownerId) {
        long conflictingVersions = entities.createQuery("""
                select count(v) from DriveItemVersion v
                where (v.owner.id = :owner or v.item.owner.id = :owner)
                  and v.owner.id <> v.item.owner.id
                """, Long.class).setParameter("owner", ownerId).getSingleResult();
        long conflictingParents = entities.createQuery("""
                select count(item) from DriveItem item
                where item.parent is not null
                  and (item.owner.id = :owner or item.parent.owner.id = :owner)
                  and item.owner.id <> item.parent.owner.id
                """, Long.class).setParameter("owner", ownerId).getSingleResult();
        if (conflictingVersions != 0 || conflictingParents != 0) {
            throw new BadRequestException("소유자가 일치하지 않는 드라이브 자료가 있어 탈퇴를 진행할 수 없습니다.");
        }
    }

    private boolean captureItems(Long ownerId, UUID requestId) {
        long cursor = 0L;
        boolean invalidPath = false;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select item.id, item.storagePath, item.fileSize, item.sourceType, item.storageBucket
                    from DriveItem item
                    where item.owner.id = :owner and item.itemType = :file and item.id > :cursor order by item.id
                    """, Object[].class).setParameter("owner", ownerId).setParameter("file", DriveItemType.FILE)
                    .setParameter("cursor", cursor).setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) return invalidPath;
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                var file = new DriveFile(ownerId, cursor, null, (String) row[1], (Long) row[2], (String) row[3], (String) row[4]);
                invalidPath |= invalidPath(file.storagePath());
                enqueue(requestId, "drive-item:" + cursor, file);
            }
        }
    }

    private boolean captureVersions(Long ownerId, UUID requestId) {
        long cursor = 0L;
        boolean invalidPath = false;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select v.id, v.item.id, v.storagePath, v.fileSize, v.item.sourceType, v.storageBucket
                    from DriveItemVersion v
                    where v.owner.id = :owner and v.id > :cursor order by v.id
                    """, Object[].class).setParameter("owner", ownerId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) return invalidPath;
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                var file = new DriveFile(ownerId, (Long) row[1], cursor, (String) row[2], (Long) row[3], (String) row[4], (String) row[5]);
                invalidPath |= invalidPath(file.storagePath());
                enqueue(requestId, "drive-version:" + cursor, file);
            }
        }
    }

    private boolean invalidPath(String path) {
        return path == null || path.isBlank() || path.chars().anyMatch(Character::isISOControl);
    }

    private void captureUploadGrants(Long ownerId, UUID requestId) {
        long cursor = 0L;
        while (true) {
            List<com.playdata.calen.drive.domain.DriveUploadGrant> rows = entities.createQuery("""
                    select grant from DriveUploadGrant grant
                    where grant.ownerId = :owner and grant.id > :cursor order by grant.id
                    """, com.playdata.calen.drive.domain.DriveUploadGrant.class)
                    .setParameter("owner", ownerId).setParameter("cursor", cursor).setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) return;
            for (var grant : rows) {
                cursor = grant.getId();
                String prefix = "drive/" + ownerId + "/";
                if (invalidPath(grant.getObjectKey()) || !grant.getObjectKey().startsWith(prefix)
                        || grant.getObjectKey().substring(prefix.length()).isBlank()
                        || grant.getObjectKey().substring(prefix.length()).contains("/")
                        || grant.getObjectKey().contains("\\") || invalidPath(grant.getStorageBucket())
                        || grant.getSignedAtUtc() == null || grant.getExpiresAtUtc() == null
                        || !grant.getExpiresAtUtc().isAfter(grant.getSignedAtUtc())) {
                    throw new BadRequestException("업로드 주소의 정리 정보를 안전하게 확인할 수 없습니다.");
                }
                try {
                    var target = new UploadGrant(ownerId, cursor, grant.getObjectKey(), grant.getStorageBucket(),
                            grant.getSignedAtUtc(), grant.getExpiresAtUtc());
                    tasks.enqueue(requestId, WithdrawalCleanupKind.DRIVE_UPLOAD_GRANT_INVENTORY,
                            "drive-upload-grant:" + cursor, json.writeValueAsString(target));
                } catch (JsonProcessingException failure) {
                    throw new ServiceUnavailableException("업로드 주소 정리 정보를 안전하게 저장할 수 없습니다.");
                }
            }
        }
    }

    private void enqueue(UUID requestId, String key, DriveFile file) {
        try {
            tasks.enqueue(requestId, WithdrawalCleanupKind.DRIVE_FILE_INVENTORY, key, json.writeValueAsString(file));
        } catch (JsonProcessingException failure) {
            throw new ServiceUnavailableException("드라이브 파일 정리 정보를 안전하게 저장할 수 없습니다.");
        }
    }

    // Null or unusual legacy paths are captured for manual review, not used as deletion targets.
    public record DriveFile(Long ownerId, Long itemId, Long versionId, String storagePath, Long fileSize, String sourceType, String storageBucket) {
        @Override public String toString() { return "DriveFile[redacted]"; }
    }

    public record InventorySummary(boolean invalidPath) {}

    public record UploadGrant(Long ownerId, Long grantId, String objectKey, String storageBucket,
                              java.time.LocalDateTime signedAtUtc, java.time.LocalDateTime expiresAtUtc) {
        @Override public String toString() { return "UploadGrant[redacted]"; }
    }
}

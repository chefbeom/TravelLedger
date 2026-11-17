package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal stage only: object tasks remain pending until their storage provenance is verified. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerImageWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,12}");
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleAndRemove(Long userId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        long cursor = 0L;
        while (true) {
            List<Object[]> images = entities.createQuery("""
                    select image.id, image.imageObjectKey, image.imageStorageBucket, image.imageStorageId, image.imageObjectVersion, image.imageObjectEtag
                    from LedgerImageAnalysisRequest image
                    where image.owner.id = :owner and image.id > :cursor order by image.id
                    """, Object[].class).setParameter("owner", userId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (images.isEmpty()) break;
            for (Object[] row : images) {
                cursor = (Long) row[0];
                String key = (String) row[1];
                if (key == null || key.isBlank()) continue;
                validateKey(userId, cursor, key);
                requireUnsharedKey(userId, key);
                try {
                    String payload = json.writeValueAsString(new ImageTask(userId, cursor, key,
                            (String) row[2], (String) row[3], (String) row[4], (String) row[5]));
                    tasks.enqueue(requestId, WithdrawalCleanupKind.LEDGER_IMAGE, "ledger-image:" + key, payload);
                } catch (JsonProcessingException exception) {
                    throw new ServiceUnavailableException("이미지 분석 원본 정리 작업을 준비할 수 없습니다.");
                }
            }
        }
        entities.createQuery("delete from LedgerImageAnalysisRequest image where image.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
    }

    private void validateKey(Long owner, Long imageId, String key) {
        if (!validKey(owner, imageId, key)) throw invalidKey();
    }

    static boolean validKey(Long owner, Long imageId, String key) {
        if (owner == null || owner <= 0 || imageId == null || imageId <= 0 || key == null) return false;
        String prefix = "ledger-image-analysis/" + owner + "/" + imageId + "/";
        return key.startsWith(prefix) && FILE_NAME.matcher(key.substring(prefix.length())).matches();
    }

    private void requireUnsharedKey(Long owner, String key) {
        for (String query : List.of(
                "select count(image) from LedgerImageAnalysisRequest image where image.owner.id <> :owner and image.imageObjectKey = :key",
                "select count(photo) from PlacePhoto photo where photo.storagePath = :key",
                "select count(asset) from TravelMediaAsset asset where asset.storagePath = :key",
                "select count(asset) from FamilyMediaAsset asset where asset.storagePath = :key",
                "select count(item) from DriveItem item where item.storagePath = :key",
                "select count(version) from DriveItemVersion version where version.storagePath = :key")) {
            var typed = entities.createQuery(query, Long.class).setParameter("key", key);
            if (query.contains(":owner")) typed.setParameter("owner", owner);
            if (typed.getSingleResult() != 0L) {
                throw new BadRequestException("다른 자료가 참조하는 이미지 분석 원본이 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
    }

    private BadRequestException invalidKey() {
        return new BadRequestException("이미지 분석 원본 경로를 안전하게 확인할 수 없습니다.");
    }

    // A bucket alone does not establish the storage server, ownership, or permission to delete.
    public record ImageTask(Long ownerId, Long analysisId, String objectKey, String bucket, String storageId, String versionId, String etag) {
        public ImageTask(Long ownerId, Long analysisId, String objectKey, String bucket, String storageId, String versionId) {
            this(ownerId, analysisId, objectKey, bucket, storageId, versionId, null);
        }
        public boolean validForVersionCleanup() {
            return validKey(ownerId, analysisId, objectKey)
                    && bucket != null && bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                    && !bucket.contains("..")
                    && com.playdata.calen.common.storage.LedgerImageStorageMarker.validId(storageId)
                    && versionId != null && !versionId.isBlank() && !versionId.equals("null")
                    && versionId.length() <= 512 && versionId.equals(versionId.trim())
                    && versionId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512
                    && versionId.chars().noneMatch(Character::isISOControl);
        }
        @Override public String toString() { return "ImageTask[redacted]"; }
    }
}

package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal stage; photo objects stay pending until a separate safe executor exists. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class RecordMapWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp");
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Value("${app.travel.media-object-prefix:travel-media}")
    private String configuredPrefix;

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
        requireNoCrossOwnerReferences(userId);
        long cursor = 0L;
        while (true) {
            List<Object[]> photos = entities.createQuery("""
                    select photo.id, photo.visit.id, photo.storagePath, photo.contentType from PlacePhoto photo
                    where photo.ownerId = :owner and photo.id > :cursor order by photo.id
                    """, Object[].class).setParameter("owner", userId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (photos.isEmpty()) break;
            for (Object[] row : photos) {
                cursor = (Long) row[0];
                Long visitId = (Long) row[1];
                String path = (String) row[2];
                String contentType = (String) row[3];
                validatePhotoPath(userId, visitId, path, contentType);
                requireUnsharedPath(userId, path);
                try {
                    String payload = json.writeValueAsString(new PhotoTask(userId, visitId, path, contentType));
                    tasks.enqueue(requestId, WithdrawalCleanupKind.RECORD_MAP_MEDIA, "record-map:" + path, payload);
                } catch (JsonProcessingException exception) {
                    throw new ServiceUnavailableException("기록 지도 사진 정리 작업을 준비할 수 없습니다.");
                }
            }
        }
        entities.createQuery("delete from RecordMapShare share where share.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createNativeQuery("delete from record_map_collection_places where place_id in "
                + "(select id from record_map_places where owner_id = :owner)")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from PlacePhoto photo where photo.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from PlaceVisit visit where visit.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from RecordPlace place where place.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from PlaceCollection collection where collection.ownerId = :owner")
                .setParameter("owner", userId).executeUpdate();
    }

    private void requireNoCrossOwnerReferences(Long owner) {
        for (String query : List.of(
                "select count(visit) from PlaceVisit visit where visit.ownerId <> :owner and visit.place.ownerId = :owner",
                "select count(photo) from PlacePhoto photo where photo.ownerId <> :owner and photo.visit.ownerId = :owner",
                "select count(share) from RecordMapShare share where share.ownerId <> :owner and share.collection.ownerId = :owner",
                "select count(place) from RecordPlace place join place.collections collection where "
                        + "(place.ownerId = :owner and collection.ownerId <> :owner) or "
                        + "(place.ownerId <> :owner and collection.ownerId = :owner)")) {
            if (entities.createQuery(query, Long.class).setParameter("owner", owner).getSingleResult() != 0L) {
                throw new BadRequestException("다른 사용자가 참조하는 기록 지도가 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
    }

    private void requireUnsharedPath(Long owner, String path) {
        for (String query : List.of(
                "select count(photo) from PlacePhoto photo where photo.ownerId <> :owner and photo.storagePath = :path",
                "select count(asset) from TravelMediaAsset asset where asset.storagePath = :path",
                "select count(asset) from FamilyMediaAsset asset where asset.storagePath = :path",
                "select count(item) from DriveItem item where item.storagePath = :path",
                "select count(version) from DriveItemVersion version where version.storagePath = :path",
                "select count(image) from LedgerImageAnalysisRequest image where image.imageObjectKey = :path")) {
            var typed = entities.createQuery(query, Long.class).setParameter("path", path);
            if (query.contains(":owner")) typed.setParameter("owner", owner);
            if (typed.getSingleResult() != 0L) {
                throw new BadRequestException("다른 자료가 참조하는 기록 지도 사진이 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
    }

    private void validatePhotoPath(Long owner, Long visit, String path, String contentType) {
        if (owner == null || owner <= 0 || visit == null || visit <= 0 || path == null
                || contentType == null || !IMAGE_TYPES.contains(contentType)) throw invalidPath();
        String prefix = configuredPrefix == null ? "" : configuredPrefix.trim().replace('\\', '/')
                .replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        if (prefix.isEmpty()) prefix = "travel-media";
        for (String part : prefix.split("/")) if (part.equals(".") || part.equals("..")) throw invalidPath();
        String relativePrefix = owner + "/0/" + visit + "/";
        String file = path.startsWith(relativePrefix) ? path.substring(relativePrefix.length())
                : path.startsWith(prefix + "/" + relativePrefix) ? path.substring((prefix + "/" + relativePrefix).length()) : "";
        if (file.length() < 38 || file.indexOf('/') >= 0 || file.indexOf('\\') >= 0
                || file.chars().anyMatch(Character::isISOControl) || file.charAt(36) != '-') throw invalidPath();
        try { UUID.fromString(file.substring(0, 36)); } catch (IllegalArgumentException exception) { throw invalidPath(); }
    }

    private BadRequestException invalidPath() { return new BadRequestException("기록 지도 사진 경로를 안전하게 확인할 수 없습니다."); }

    public record PhotoTask(Long ownerId, Long visitId, String path, String contentType) {}
}

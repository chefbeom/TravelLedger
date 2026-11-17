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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes only the departing user's unshared family data after ownership transfers. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class FamilyAlbumWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[a-zA-Z0-9._-]+");
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Value("${app.family.media-object-prefix:family-media}")
    private String configuredPrefix;

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleAndRemove(Long userId, UUID requestId) {
        // Persist this transaction's ownership transfers and deactivation before re-reading.
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        requireNoForeignReferences(userId);
        long cursor = 0L;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select asset.id, asset.category.id, asset.storagePath, asset.storedFileName, asset.contentType
                    from FamilyMediaAsset asset where asset.owner.id = :owner and asset.id > :cursor order by asset.id
                    """, Object[].class).setParameter("owner", userId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                Long categoryId = (Long) row[1];
                String path = (String) row[2], storedName = (String) row[3], contentType = (String) row[4];
                validatePath(categoryId, path, storedName, contentType);
                requireUnsharedPath(userId, path);
                try {
                    String payload = json.writeValueAsString(new MediaTask(userId, cursor, categoryId, path, contentType));
                    tasks.enqueue(requestId, WithdrawalCleanupKind.FAMILY_MEDIA, "family-media:" + path, payload);
                } catch (JsonProcessingException exception) {
                    throw new ServiceUnavailableException("가족 사진 정리 작업을 준비할 수 없습니다.");
                }
            }
        }
        entities.createQuery("delete from FamilyAlbumItem item where item.album.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from FamilyAlbum album where album.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from FamilyMediaAsset asset where asset.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from FamilyCategoryMember member where member.user.id = :owner or member.category.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from FamilyCategory category where category.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
    }

    private void requireNoForeignReferences(Long owner) {
        for (String query : List.of(
                "select count(album) from FamilyAlbum album where album.owner.id = :owner and album.category.owner.id <> :owner",
                "select count(album) from FamilyAlbum album where album.owner.id <> :owner and album.category.owner.id = :owner",
                "select count(asset) from FamilyMediaAsset asset where asset.owner.id <> :owner and asset.category.owner.id = :owner",
                "select count(member) from FamilyCategoryMember member where member.user.id <> :owner and member.category.owner.id = :owner",
                "select count(item) from FamilyAlbumItem item where "
                        + "(item.media.owner.id = :owner and item.album.owner.id <> :owner) or "
                        + "(item.album.owner.id = :owner and item.media.owner.id <> :owner)")) {
            if (entities.createQuery(query, Long.class).setParameter("owner", owner).getSingleResult() != 0L) {
                throw new BadRequestException("다른 사용자가 참조하는 가족 앨범이 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
    }

    private void requireUnsharedPath(Long owner, String path) {
        for (String query : List.of(
                "select count(asset) from FamilyMediaAsset asset where asset.owner.id <> :owner and asset.storagePath = :path",
                "select count(asset) from TravelMediaAsset asset where asset.storagePath = :path",
                "select count(photo) from PlacePhoto photo where photo.storagePath = :path",
                "select count(item) from DriveItem item where item.storagePath = :path",
                "select count(version) from DriveItemVersion version where version.storagePath = :path",
                "select count(image) from LedgerImageAnalysisRequest image where image.imageObjectKey = :path")) {
            var typed = entities.createQuery(query, Long.class).setParameter("path", path);
            if (query.contains(":owner")) typed.setParameter("owner", owner);
            if (typed.getSingleResult() != 0L) {
                throw new BadRequestException("다른 자료가 참조하는 가족 사진이 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
    }

    private void validatePath(Long categoryId, String path, String storedName, String contentType) {
        if (categoryId == null || categoryId <= 0 || path == null || storedName == null || contentType == null
                || !FILE_NAME.matcher(storedName).matches()) throw invalidPath();
        String prefix = configuredPrefix == null ? "" : configuredPrefix.trim().replace('\\', '/')
                .replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        if (prefix.isEmpty()) prefix = "family-media";
        for (String part : prefix.split("/")) if (part.equals(".") || part.equals("..")) throw invalidPath();
        String relative = path.startsWith(prefix + "/") ? path.substring(prefix.length() + 1) : path;
        String[] parts = relative.split("/", -1);
        if (parts.length != 3 || !parts[0].matches("[1-9][0-9]{0,18}") || !parts[1].matches("[1-9][0-9]{0,18}")
                || !storedName.equals(parts[2])) throw invalidPath();
        try {
            if (Long.parseLong(parts[0]) <= 0 || Long.parseLong(parts[1]) != categoryId) throw invalidPath();
        } catch (NumberFormatException exception) { throw invalidPath(); }
    }

    private BadRequestException invalidPath() { return new BadRequestException("가족 사진 경로를 안전하게 확인할 수 없습니다."); }

    public record MediaTask(Long ownerId, Long assetId, Long categoryId, String path, String contentType) {}
}

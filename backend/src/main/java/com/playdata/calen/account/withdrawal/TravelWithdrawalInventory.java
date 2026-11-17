package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.service.TravelPlanStorageProvenance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal inventory only: neither metadata nor storage objects are removed here.
 * A separate future cleanup must recheck all references, storage provenance, prepared
 * thumbnails and outstanding presigned uploads before deleting anything.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelWithdrawalInventory {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TravelPlanStorageProvenance provenance;

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(Long owner, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(owner)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(owner).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        requireConsistentMediaOwners(owner);
        captureMedia(owner, requestId);
        captureRouteFiles(owner, requestId);
    }

    private void requireConsistentMediaOwners(Long owner) {
        Long count = entities.createQuery("""
                select count(asset) from TravelMediaAsset asset
                where (asset.plan.owner.id = :owner or asset.record.plan.owner.id = :owner or asset.uploadedBy.id = :owner)
                  and (asset.plan.owner.id <> asset.uploadedBy.id or asset.plan.id <> asset.record.plan.id)
                """, Long.class).setParameter("owner", owner).getSingleResult();
        if (count != 0L) {
            throw new BadRequestException("소유자가 다른 여행 사진이 있어 탈퇴 자료를 안전하게 확인할 수 없습니다.");
        }
    }

    private void captureMedia(Long owner, UUID requestId) {
        long cursor = 0L;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select asset.id, asset.plan, asset.record.id, asset.storagePath,
                           asset.storedFileName, asset.contentType, asset.fileSize
                    from TravelMediaAsset asset
                    where asset.plan.owner.id = :owner and asset.id > :cursor order by asset.id
                    """, Object[].class).setParameter("owner", owner).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) return;
            List<TravelFile> files = new ArrayList<>();
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                TravelPlan plan = (TravelPlan) row[1]; Long record = (Long) row[2];
                String path = (String) row[3], storedName = (String) row[4], contentType = (String) row[5];
                Long size = (Long) row[6];
                long origin = provenance.requirePath(plan, record, false, path, storedName, contentType, size);
                files.add(new TravelFile(owner, plan.getId(), record, cursor, "MEDIA", path, contentType, size, origin));
            }
            requireNoForeignDriveReferences(owner, "TRAVEL_MEDIA",
                    rows.stream().map(row -> row[0].toString()).toList(), files);
            files.forEach(file -> enqueue(requestId, file));
        }
    }

    private void captureRouteFiles(Long owner, UUID requestId) {
        long cursor = 0L;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select route.id, route.plan, route.gpxFilesJson from TravelRouteSegment route
                    where route.plan.owner.id = :owner and route.id > :cursor order by route.id
                    """, Object[].class).setParameter("owner", owner).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) return;
            List<TravelFile> files = new ArrayList<>();
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                TravelPlan plan = (TravelPlan) row[1];
                String raw = (String) row[2];
                if (raw == null || raw.isBlank()) continue;
                JsonNode gpxFiles;
                try { gpxFiles = json.readTree(raw); }
                catch (JsonProcessingException failure) { throw invalidMetadata(); }
                if (gpxFiles == null || !gpxFiles.isArray()) throw invalidMetadata();
                for (JsonNode file : gpxFiles) {
                    if (!file.isObject() || !file.path("storagePath").isTextual()
                            || !file.path("contentType").isTextual() || !file.path("fileSize").isIntegralNumber()
                            || !file.path("fileSize").canConvertToLong()) throw invalidMetadata();
                    String path = file.path("storagePath").textValue();
                    String type = file.path("contentType").textValue();
                    long size = file.path("fileSize").longValue();
                    long origin = provenance.requirePath(plan, cursor, true, path, null, type, size);
                    files.add(new TravelFile(owner, plan.getId(), null, cursor, "GPX", path, type, size, origin));
                }
            }
            // Include every route ID, even when its GPX list is empty, to detect stale links.
            requireNoForeignDriveReferences(owner, "TRAVEL_GPX",
                    rows.stream().map(row -> row[0].toString()).toList(), files);
            files.forEach(file -> enqueue(requestId, file));
        }
    }

    private void requireNoForeignDriveReferences(Long owner, String sourceType, List<String> sourceIds,
                                                 List<TravelFile> files) {
        Long references = entities.createQuery("""
                select count(item) from DriveItem item
                where item.owner.id <> :owner and item.sourceType = :sourceType and item.sourceReference in :sourceIds
                """, Long.class).setParameter("owner", owner).setParameter("sourceType", sourceType)
                .setParameter("sourceIds", sourceIds)
                .getSingleResult();
        if (references != 0L) throw foreignReference();
        List<String> paths = files.stream().map(TravelFile::storagePath).toList();
        for (int start = 0; start < paths.size(); start += PAGE_SIZE) {
            List<String> batch = paths.subList(start, Math.min(start + PAGE_SIZE, paths.size()));
            references = entities.createQuery("""
                    select count(item) from DriveItem item where item.owner.id <> :owner and item.storagePath in :paths
                    """, Long.class).setParameter("owner", owner).setParameter("paths", batch).getSingleResult();
            if (references != 0L) throw foreignReference();
            references = entities.createQuery("""
                    select count(version) from DriveItemVersion version
                    where (version.owner.id <> :owner or version.item.owner.id <> :owner) and version.storagePath in :paths
                    """, Long.class).setParameter("owner", owner).setParameter("paths", batch).getSingleResult();
            if (references != 0L) throw foreignReference();
        }
    }

    private BadRequestException foreignReference() {
        return new BadRequestException("다른 사용자가 참조하는 여행 자료가 있어 탈퇴를 안전하게 진행할 수 없습니다.");
    }

    private void enqueue(UUID requestId, TravelFile file) {
        try {
            tasks.enqueue(requestId, WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY,
                    "travel-file:" + file.storagePath(), json.writeValueAsString(file));
        } catch (JsonProcessingException failure) {
            throw new ServiceUnavailableException("여행 파일 정리 정보를 안전하게 저장할 수 없습니다.");
        }
    }

    private BadRequestException invalidMetadata() {
        return new BadRequestException("여행 파일의 소유권 또는 경로를 안전하게 확인할 수 없습니다.");
    }

    public record TravelFile(Long ownerId, Long planId, Long recordId, Long sourceId,
            String sourceType, String storagePath, String contentType, Long fileSize, Long storageOriginId) {
        @Override public String toString() { return "TravelFile[redacted]"; }
    }
}

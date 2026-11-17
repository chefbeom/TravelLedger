package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.travel.application.port.out.OwnedTravelMediaCachePort;
import com.playdata.calen.travel.application.port.out.TravelClusterSnapshotCachePort;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal transfer step. It is not an endpoint and never deletes or copies an object. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelPlanTransferService {
    private static final List<RecordShareStatus> LIVE = List.of(RecordShareStatus.PENDING, RecordShareStatus.ACCEPTED);
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final TravelPlanWithdrawalService preview;
    private final TravelPlanStorageProvenance provenance;
    private final TravelDriveLinkService drive;
    private final TravelClusterRefreshCoordinator clusters;
    private final TravelClusterSnapshotCachePort snapshots;
    private final TravelSummaryInvalidationPort summaries;
    private final OwnedTravelMediaCachePort mediaCache;
    private final ObjectMapper json;
    private final EntityManager entities;

    /** The caller must hold the withdrawing account lock and an active withdrawal transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferForWithdrawal(Long ownerId, UUID requestId, Map<Long, Long> selections) {
        entities.flush();
        AppUser owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow(this::unavailable);
        entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(ownerId).orElse(null);
        if (!owner.isActive() || request == null || requestId == null || !requestId.toString().equals(request.getId())
                || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS) throw unavailable();
        Map<Long, Long> selected = selections == null ? Map.of() : new HashMap<>(selections);
        List<TravelPlanWithdrawalService.TransferChoice> initial = preview.requiredTransfers(ownerId);
        requireCompleteSelections(initial, selected);
        if (selected.isEmpty()) return;

        Map<Long, AppUser> targets = new HashMap<>();
        for (Long id : new TreeSet<>(selected.values())) {
            AppUser target = users.lockForAccountSecurityUpdate(id).orElseThrow(this::unavailable);
            entities.refresh(target, LockModeType.PESSIMISTIC_WRITE);
            if (!target.isActive() || id.equals(ownerId) || requests.existsByUserId(id)) throw unavailable();
            targets.put(id, target);
        }
        List<Long> planIds = selected.keySet().stream().sorted().toList();
        List<Long> groupIds = entities.createQuery("""
                select distinct share.groupId from RecordShare share
                where share.kind = :kind and share.sourceId in :plans and share.status in :statuses
                order by share.groupId
                """, Long.class).setParameter("kind", RecordShareKind.TRAVEL).setParameter("plans", planIds)
                .setParameter("statuses", LIVE).getResultList();
        if (!groupIds.isEmpty()) {
            List<TravelShareGroup> lockedGroups = entities.createQuery("""
                    select grp from TravelShareGroup grp where grp.id in :ids order by grp.id
                    """, TravelShareGroup.class).setParameter("ids", groupIds)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            if (lockedGroups.size() != groupIds.size()) throw unavailable();
            lockedGroups.forEach(group -> entities.refresh(group, LockModeType.PESSIMISTIC_WRITE));
            List<TravelShareGroupMember> members = entities.createQuery("""
                    select member from TravelShareGroupMember member where member.group.id in :ids
                    order by member.group.id, member.id
                    """, TravelShareGroupMember.class).setParameter("ids", groupIds)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            members.forEach(member -> entities.refresh(member, LockModeType.PESSIMISTIC_WRITE));
        }
        List<TravelPlan> plans = entities.createQuery("""
                select plan from TravelPlan plan where plan.id in :ids and plan.owner.id = :owner order by plan.id
                """, TravelPlan.class).setParameter("ids", planIds).setParameter("owner", ownerId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (plans.size() != planIds.size()) throw unavailable();
        plans.forEach(plan -> entities.refresh(plan, LockModeType.PESSIMISTIC_WRITE));
        List<TravelPlanShare> direct = entities.createQuery("""
                select share from TravelPlanShare share where share.plan.id in :ids order by share.plan.id, share.id
                """, TravelPlanShare.class).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        direct.forEach(share -> entities.refresh(share, LockModeType.PESSIMISTIC_WRITE));
        List<RecordShare> grouped = entities.createQuery("""
                select share from RecordShare share where share.kind = :kind and share.sourceId in :ids
                and share.status in :statuses order by share.sourceId, share.id
                """, RecordShare.class).setParameter("kind", RecordShareKind.TRAVEL).setParameter("ids", planIds)
                .setParameter("statuses", LIVE).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        grouped.forEach(share -> entities.refresh(share, LockModeType.PESSIMISTIC_WRITE));
        requireCompleteSelections(preview.requiredTransfers(ownerId), selected);
        long liveGrants = entities.createQuery("""
                select count(grant) from TravelUploadGrant grant
                where grant.planId in :ids and grant.expiresAtUtc > :cutoff
                """, Long.class).setParameter("ids", planIds)
                .setParameter("cutoff", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)).getSingleResult();
        if (liveGrants != 0) throw new BadRequestException("여행 파일 업로드 주소가 만료된 뒤 다시 시도해 주세요.");

        List<TravelMediaAsset> media = entities.createQuery("""
                select asset from TravelMediaAsset asset where asset.plan.id in :ids order by asset.id
                """, TravelMediaAsset.class).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        media.forEach(asset -> entities.refresh(asset, LockModeType.PESSIMISTIC_WRITE));
        List<TravelRouteSegment> routes = entities.createQuery("""
                select route from TravelRouteSegment route where route.plan.id in :ids order by route.id
                """, TravelRouteSegment.class).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        routes.forEach(route -> entities.refresh(route, LockModeType.PESSIMISTIC_WRITE));
        List<TravelExpenseRecord> publicRecords = entities.createQuery("""
                select record from TravelExpenseRecord record
                where record.plan.id in :ids and record.sharedWithCommunity = true order by record.id
                """, TravelExpenseRecord.class).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        publicRecords.forEach(record -> entities.refresh(record, LockModeType.PESSIMISTIC_WRITE));
        Map<Long, List<TravelDriveLinkService.TravelLinkedFile>> routeFiles = new HashMap<>();
        for (TravelMediaAsset asset : media) {
            if (asset.getRecord() == null || asset.getRecord().getPlan() == null || asset.getPlan() == null
                    || !asset.getPlan().getId().equals(asset.getRecord().getPlan().getId()) || asset.getUploadedBy() == null) {
                throw unavailable();
            }
            provenance.requirePath(asset.getPlan(), asset.getRecord().getId(), false, asset.getStoragePath(),
                    asset.getStoredFileName(), asset.getContentType(), asset.getFileSize());
        }
        for (TravelRouteSegment route : routes) routeFiles.put(route.getId(), validateRouteFiles(route));
        for (TravelPlanShare share : direct) {
            if (share.getSharedBy() == null || !ownerId.equals(share.getSharedBy().getId())
                    || share.getRecipient() == null || share.getPlan() == null) throw unavailable();
        }
        for (RecordShare share : grouped) {
            if (share.getSender() == null || !ownerId.equals(share.getSender().getId()) || share.getRecipient() == null
                    || !groupIds.contains(share.getGroupId())) throw unavailable();
        }

        for (TravelPlan plan : plans) {
            provenance.rememberCurrentOwner(plan);
            // Existing public consent belongs to the departing owner, not the successor.
            plan.setPublicShared(false);
            plan.setPublicSharedAt(null);
            plan.setOwner(targets.get(selected.get(plan.getId())));
        }
        publicRecords.forEach(record -> record.setSharedWithCommunity(false));
        for (TravelMediaAsset asset : media) {
            if (ownerId.equals(asset.getUploadedBy().getId())) asset.setUploadedBy(targets.get(selected.get(asset.getPlan().getId())));
        }
        for (TravelPlanShare share : direct) {
            Long successor = selected.get(share.getPlan().getId());
            if (successor.equals(share.getRecipient().getId())) entities.remove(share);
            else share.setSharedBy(targets.get(successor));
        }
        for (RecordShare share : grouped) {
            Long successor = selected.get(share.getSourceId());
            if (successor.equals(share.getRecipient().getId())) entities.remove(share);
            else share.setSender(targets.get(successor));
        }
        entities.flush();
        for (TravelPlan plan : plans) drive.ensurePlanFolder(plan);
        for (TravelMediaAsset asset : media) {
            drive.linkMediaAsset(asset);
            mediaCache.invalidate(ownerId, asset.getId());
            mediaCache.invalidate(asset.getPlan().getOwner().getId(), asset.getId());
        }
        for (TravelRouteSegment route : routes) {
            List<TravelDriveLinkService.TravelLinkedFile> files = routeFiles.get(route.getId());
            if (!files.isEmpty()) drive.replaceRouteGpxLinks(route, files);
        }
        summaries.invalidateOwner(ownerId);
        Set<Long> successorIds = new HashSet<>(selected.values());
        for (Long successor : successorIds) {
            summaries.invalidateOwner(successor);
            snapshots.invalidateNow(successor);
            snapshots.invalidateAfterCommit(successor);
            clusters.markDirtyInCurrentTransaction(successor);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Transfer requires transaction synchronization.");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { successorIds.forEach(clusters::dispatch); }
        });
    }

    private void requireCompleteSelections(List<TravelPlanWithdrawalService.TransferChoice> choices, Map<Long, Long> selected) {
        if (choices.stream().anyMatch(choice -> choice.publiclyShared() && choice.members().isEmpty())) {
            throw new BadRequestException("공개된 여행은 먼저 이전받을 회원에게 공유해 주세요.");
        }
        if (selected.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)
                || !selected.keySet().equals(choices.stream().map(TravelPlanWithdrawalService.TransferChoice::planId).collect(Collectors.toSet()))) {
            throw new BadRequestException("공유 여행마다 이전받을 멤버를 선택해 주세요.");
        }
        for (var choice : choices) {
            if (choice.members().stream().noneMatch(member -> member.eligible() && member.userId().equals(selected.get(choice.planId())))) {
                throw new BadRequestException("기존 공유를 유지할 수 있는 활성 여행 공유 멤버를 선택해 주세요.");
            }
        }
    }

    private List<TravelDriveLinkService.TravelLinkedFile> validateRouteFiles(TravelRouteSegment route) {
        String raw = route.getGpxFilesJson();
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode files;
        try { files = json.readTree(raw); }
        catch (JsonProcessingException failure) { throw unavailable(); }
        if (files == null || !files.isArray()) throw unavailable();
        List<TravelDriveLinkService.TravelLinkedFile> result = new ArrayList<>();
        for (JsonNode file : files) {
            if (!file.isObject() || !file.path("storagePath").isTextual() || !file.path("contentType").isTextual()
                    || !file.path("fileSize").isIntegralNumber() || !file.path("fileSize").canConvertToLong()
                    || (!file.path("originalFileName").isMissingNode() && !file.path("originalFileName").isTextual())) throw unavailable();
            String path = file.path("storagePath").textValue(), type = file.path("contentType").textValue();
            long size = file.path("fileSize").longValue();
            provenance.requirePath(route.getPlan(), route.getId(), true, path, null, type, size);
            String name = file.path("originalFileName").isTextual() ? file.path("originalFileName").textValue() : null;
            result.add(new TravelDriveLinkService.TravelLinkedFile(name, path, type, size));
        }
        return List.copyOf(result);
    }

    private BadRequestException unavailable() {
        return new BadRequestException("여행 계획과 공유 자료를 안전하게 이전할 수 없습니다. 공유 멤버와 파일을 확인해 주세요.");
    }
}

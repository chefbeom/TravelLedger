package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.out.OwnedTravelMediaCachePort;
import com.playdata.calen.travel.application.port.out.TravelClusterSnapshotCachePort;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import com.playdata.calen.travel.service.TravelPlanStorageProvenance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Preserves media already belonging to another user's plan without moving or deleting files. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelForeignMediaWithdrawalService {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final TravelPlanStorageProvenance provenance;
    private final OwnedTravelMediaCachePort mediaCache;
    private final TravelSummaryInvalidationPort summaries;
    private final TravelClusterSnapshotCachePort snapshots;
    private final TravelClusterRefreshCoordinator clusters;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void preserveForPlanOwners(Long departingId, UUID requestId) {
        entities.flush();
        AppUser departing = users.lockForAccountSecurityUpdate(departingId).orElseThrow(this::unavailable);
        entities.refresh(departing, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(departingId).orElse(null);
        if (!departing.isActive() || request == null || requestId == null
                || !requestId.toString().equals(request.getId())
                || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS) throw unavailable();

        List<Object[]> references = entities.createQuery("""
                select distinct asset.plan.id, asset.plan.owner.id from TravelMediaAsset asset
                where asset.uploadedBy.id = :departing and asset.plan.owner.id <> :departing
                """, Object[].class).setParameter("departing", departingId).getResultList();
        if (references.isEmpty()) return;
        TreeSet<Long> planIds = new TreeSet<>(), ownerIds = new TreeSet<>();
        for (Object[] reference : references) {
            planIds.add((Long) reference[0]);
            ownerIds.add((Long) reference[1]);
        }
        Map<Long, AppUser> owners = new HashMap<>();
        for (Long ownerId : ownerIds) {
            AppUser owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow(this::unavailable);
            entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
            if (!owner.isActive() || requests.existsByUserId(ownerId)) throw unavailable();
            owners.put(ownerId, owner);
        }
        List<TravelPlan> plans = entities.createQuery("""
                select plan from TravelPlan plan where plan.id in :ids order by plan.id
                """, TravelPlan.class).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (plans.size() != planIds.size()) throw unavailable();
        for (TravelPlan plan : plans) {
            entities.refresh(plan, LockModeType.PESSIMISTIC_WRITE);
            if (plan.getOwner() == null || !owners.containsKey(plan.getOwner().getId())) throw unavailable();
        }
        long activeGrants = entities.createQuery("""
                select count(grant) from TravelUploadGrant grant
                where grant.planId in :ids and grant.expiresAtUtc > :cutoff
                """, Long.class).setParameter("ids", planIds)
                .setParameter("cutoff", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)).getSingleResult();
        if (activeGrants != 0) throw new BadRequestException("여행 파일 업로드 주소가 만료된 뒤 다시 시도해 주세요.");

        List<TravelMediaAsset> assets = entities.createQuery("""
                select asset from TravelMediaAsset asset
                where asset.uploadedBy.id = :departing and asset.plan.id in :ids order by asset.id
                """, TravelMediaAsset.class).setParameter("departing", departingId).setParameter("ids", planIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (TravelMediaAsset asset : assets) {
            entities.refresh(asset, LockModeType.PESSIMISTIC_WRITE);
            if (asset.getPlan() == null || asset.getPlan().getOwner() == null || asset.getRecord() == null
                    || asset.getRecord().getPlan() == null || asset.getUploadedBy() == null
                    || !departingId.equals(asset.getUploadedBy().getId())
                    || !asset.getPlan().getId().equals(asset.getRecord().getPlan().getId())
                    || !owners.containsKey(asset.getPlan().getOwner().getId())) throw unavailable();
            provenance.requirePath(asset.getPlan(), asset.getRecord().getId(), false, asset.getStoragePath(),
                    asset.getStoredFileName(), asset.getContentType(), asset.getFileSize());
            Long successor = asset.getPlan().getOwner().getId();
            asset.setUploadedBy(owners.get(successor));
            mediaCache.invalidate(departingId, asset.getId());
            mediaCache.invalidate(successor, asset.getId());
        }
        entities.flush();
        long remaining = entities.createQuery("""
                select count(asset) from TravelMediaAsset asset
                where asset.uploadedBy.id = :departing and asset.plan.owner.id <> :departing
                """, Long.class).setParameter("departing", departingId).getSingleResult();
        if (remaining != 0) throw unavailable();
        for (Long ownerId : ownerIds) {
            summaries.invalidateOwner(ownerId);
            snapshots.invalidateNow(ownerId);
            snapshots.invalidateAfterCommit(ownerId);
            clusters.markDirtyInCurrentTransaction(ownerId);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Transfer requires transaction synchronization.");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { ownerIds.forEach(clusters::dispatch); }
        });
    }

    private BadRequestException unavailable() {
        return new BadRequestException("다른 사람의 여행에 보존할 사진·영수증의 소유권을 안전하게 확인할 수 없습니다.");
    }
}

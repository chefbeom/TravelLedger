package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes only untransferred travel rows, after their exact file locations are inventoried. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelMetadataWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final TravelWithdrawalInventory inventory;
    private final EntityManager entities;

    /** Run before deleting derived clusters or other rows that might expose an inconsistent reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoForeignReferences(Long ownerId, UUID requestId) {
        requireWithdrawal(ownerId, requestId);
        for (String query : List.of(
                "select count(share) from TravelPlanShare share where share.plan.owner.id = :owner",
                "select count(share) from RecordShare share where share.kind = com.playdata.calen.sharing.domain.RecordShareKind.TRAVEL "
                        + "and share.sourceId in (select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "select count(entry) from LedgerEntry entry where entry.owner.id <> :owner and "
                        + "(entry.travelPlanId in (select plan.id from TravelPlan plan where plan.owner.id = :owner) or "
                        + "entry.travelRecordId in (select record.id from TravelExpenseRecord record where record.plan.owner.id = :owner))",
                "select count(place) from RecordPlace place where place.ownerId <> :owner and place.sourceTravelRecordId in "
                        + "(select record.id from TravelExpenseRecord record where record.plan.owner.id = :owner)",
                "select count(cluster) from TravelPhotoCluster cluster where cluster.ownerId <> :owner and "
                        + "(cluster.planId in (select plan.id from TravelPlan plan where plan.owner.id = :owner) or "
                        + "cluster.representativeRecordId in (select record.id from TravelExpenseRecord record where record.plan.owner.id = :owner) or "
                        + "cluster.representativeMediaId in (select asset.id from TravelMediaAsset asset where asset.plan.owner.id = :owner))",
                "select count(member) from TravelPhotoClusterMember member where "
                        + "(member.ownerId <> :owner and (member.mediaId in "
                        + "(select asset.id from TravelMediaAsset asset where asset.plan.owner.id = :owner) or member.clusterId in "
                        + "(select cluster.id from TravelPhotoCluster cluster where cluster.ownerId = :owner))) or "
                        + "(member.ownerId = :owner and member.clusterId in "
                        + "(select cluster.id from TravelPhotoCluster cluster where cluster.ownerId <> :owner))",
                "select count(grant) from TravelUploadGrant grant where grant.ownerId <> :owner and grant.planId in "
                        + "(select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "select count(item) from DriveItem item where item.owner.id <> :owner "
                        + "and item.storagePath in (select asset.storagePath from TravelMediaAsset asset where asset.plan.owner.id = :owner)")) {
            if (entities.createQuery(query, Long.class).setParameter("owner", ownerId).getSingleResult() != 0L) {
                throw new BadRequestException("다른 사용자가 참조하는 여행 자료가 있어 탈퇴를 안전하게 진행할 수 없습니다.");
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void captureAndRemove(Long ownerId, UUID requestId) {
        // Inventory tasks and metadata removal commit or roll back together. No object is deleted here.
        inventory.capture(ownerId, requestId);
        requireNoForeignReferences(ownerId, requestId);
        for (String query : List.of(
                "delete from TravelMediaAsset asset where asset.plan.id in "
                        + "(select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "delete from TravelRouteSegment route where route.plan.id in "
                        + "(select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "delete from TravelBudgetItem budget where budget.plan.id in "
                        + "(select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "delete from TravelExpenseRecord record where record.plan.id in "
                        + "(select plan.id from TravelPlan plan where plan.owner.id = :owner)",
                "delete from TravelPlan plan where plan.owner.id = :owner")) {
            entities.createQuery(query).setParameter("owner", ownerId).executeUpdate();
        }
    }

    private void requireWithdrawal(Long ownerId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(ownerId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
    }
}

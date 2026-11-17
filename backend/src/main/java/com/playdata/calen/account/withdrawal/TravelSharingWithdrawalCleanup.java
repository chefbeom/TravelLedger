package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.service.TravelLoginMapPreviewService;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Removes the departing user's sharing edges after group ownership transfers. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelSharingWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TravelLoginMapPreviewService loginPreview;
    private final TravelPlanWithdrawalService travelPlans;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeEdges(Long userId, UUID requestId) {
        // Flush this transaction's deactivation and ownership transfers before
        // refreshing any request-scoped account that may be stale.
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        travelPlans.requireNoOwnedSharedPlansRemain(userId);
        for (String query : List.of(
                "select count(share) from TravelPlanShare share where "
                        + "(share.sharedBy.id = :owner or share.recipient.id = :owner or share.plan.owner.id = :owner) "
                        + "and share.sharedBy.id <> share.plan.owner.id",
                "select count(member) from TravelShareGroupMember member where member.group.owner.id = :owner "
                        + "and member.member.id <> :owner",
                "select count(share) from RecordShare share where share.groupId in "
                        + "(select grp.id from TravelShareGroup grp where grp.owner.id = :owner)")) {
            if (entities.createQuery(query, Long.class).setParameter("owner", userId).getSingleResult() != 0L) {
                throw new BadRequestException("다른 사용자가 참조하는 여행 공유가 있어 탈퇴를 진행할 수 없습니다.");
            }
        }
        entities.createQuery("delete from TravelPlanShare share where share.sharedBy.id = :owner "
                        + "or share.recipient.id = :owner or share.plan.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        clearLoginPreviewForOwnedLink(userId);
        entities.createQuery("delete from TravelMapShareLink link where link.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from TravelShareGroupMember member where member.member.id = :owner "
                        + "or member.group.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        entities.createQuery("delete from TravelShareGroup grp where grp.owner.id = :owner")
                .setParameter("owner", userId).executeUpdate();
    }

    private void clearLoginPreviewForOwnedLink(Long owner) {
        var selected = jdbc.queryForList("""
                select setting.share_token from travel_login_map_preview_settings setting
                join travel_map_share_links link on link.token = setting.share_token
                where setting.setting_id = 1 and link.owner_id = ?
                """, String.class, owner);
        if (selected.isEmpty()) return;
        String token = selected.get(0);
        int changed = jdbc.update("""
                update travel_login_map_preview_settings
                set enabled = false, share_token = null, updated_at = current_timestamp
                where setting_id = 1 and share_token = ?
                """, token);
        if (changed == 0) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Withdrawal cleanup requires transaction synchronization.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { loginPreview.forgetRemovedShare(token); }
        });
    }
}

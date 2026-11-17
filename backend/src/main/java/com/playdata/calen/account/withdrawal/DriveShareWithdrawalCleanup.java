package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.domain.DriveItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes only direct-share edges involving the withdrawing user, never files or copies. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class DriveShareWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeDirectShares(Long userId, UUID requestId) {
        // The caller has just changed the user to inactive in this transaction.
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        Long inconsistent = entities.createQuery("""
                select count(share) from DriveShare share
                where (share.owner.id = :owner or share.recipient.id = :owner or share.item.owner.id = :owner)
                  and share.owner.id <> share.item.owner.id
                """, Long.class).setParameter("owner", userId).getSingleResult();
        if (inconsistent != 0L) {
            throw new BadRequestException("소유자를 확인할 수 없는 드라이브 공유가 있어 탈퇴를 진행할 수 없습니다.");
        }

        long cursor = 0L;
        while (true) {
            List<Long> itemIds = entities.createQuery("""
                    select distinct share.item.id from DriveShare share
                    where share.item.id > :cursor
                      and (share.owner.id = :owner or share.recipient.id = :owner)
                    order by share.item.id
                    """, Long.class).setParameter("cursor", cursor).setParameter("owner", userId)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (itemIds.isEmpty()) return;
            for (Long itemId : itemIds) {
                cursor = itemId;
                DriveItem item = entities.find(DriveItem.class, itemId, LockModeType.PESSIMISTIC_WRITE);
                if (item == null) throw new BadRequestException("드라이브 공유 파일을 확인할 수 없습니다.");
                entities.refresh(item, LockModeType.PESSIMISTIC_WRITE);
                // Normal share creation/cancellation also locks the item first. Query the
                // remaining edges only after this lock so concurrent changes are not lost.
                entities.createQuery("""
                        delete from DriveShare share where share.item.id = :item
                          and (share.owner.id = :owner or share.recipient.id = :owner)
                        """).setParameter("item", itemId).setParameter("owner", userId).executeUpdate();
                Long remaining = entities.createQuery("select count(share) from DriveShare share where share.item.id = :item", Long.class)
                        .setParameter("item", itemId).getSingleResult();
                item.setSharedFile(remaining > 0L);
            }
            entities.flush();
        }
    }
}

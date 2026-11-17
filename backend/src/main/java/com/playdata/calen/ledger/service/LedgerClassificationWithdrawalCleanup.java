package com.playdata.calen.ledger.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes only the departing owner's classification rules as part of an internal withdrawal transaction. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerClassificationWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeRules(Long userId) {
        requireWithdrawal(userId);
        entities.flush();
        entities.createQuery("delete from LedgerClassificationRule rule where rule.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeCatalog(Long userId) {
        requireWithdrawal(userId);
        entities.flush();
        // Corrupt or legacy cross-owner references must never make another user's data disappear
        // or become orphaned. Stop the entire withdrawal transaction for manual investigation.
        for (String type : java.util.List.of("LedgerEntry", "RecurringLedgerRule", "LedgerClassificationRule")) {
            Long references = entities.createQuery("select count(item) from " + type + " item " + """
                    where item.owner.id <> :userId and (
                        item.categoryGroup.id in (select g.id from CategoryGroup g where g.owner.id = :userId)
                        or item.categoryDetail.id in (select d.id from CategoryDetail d where d.group.owner.id = :userId)
                        or item.paymentMethod.id in (select p.id from PaymentMethod p where p.owner.id = :userId))
                    """, Long.class).setParameter("userId", userId).getSingleResult();
            if (references != 0L) throw new BadRequestException("다른 사용자가 참조하는 분류가 있어 탈퇴를 진행할 수 없습니다.");
        }
        entities.createQuery("delete from CategoryDetail d where d.group.id in "
                + "(select g.id from CategoryGroup g where g.owner.id = :userId)")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from CategoryGroup g where g.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from PaymentMethod p where p.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
    }

    private void requireWithdrawal(Long userId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
    }
}

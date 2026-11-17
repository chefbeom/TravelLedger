package com.playdata.calen.ledger.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingOutboxCapture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal step only. Does not complete withdrawal or call the external embedding service. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class RecurringLedgerWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final LedgerEmbeddingOutboxCapture outbox;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeRulesAndOccurrences(Long userId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        // Bulk JPQL bypasses entity listeners, so tombstones must be written first, in this transaction.
        outbox.beforeWithdrawalRecurringRules(userId);
        entities.createQuery("delete from RecurringLedgerOccurrence occurrence where occurrence.rule.id in "
                + "(select rule.id from RecurringLedgerRule rule where rule.owner.id = :userId)")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from RecurringLedgerRule rule where rule.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
    }
}

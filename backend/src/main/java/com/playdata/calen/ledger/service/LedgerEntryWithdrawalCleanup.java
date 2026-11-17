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

/** Internal owner-scoped DB step only; independent entries belonging to other users are preserved. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class LedgerEntryWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final LedgerEmbeddingOutboxCapture outbox;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeEntriesAndHistories(Long userId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isActive() || requests.findByUserId(userId)
                .filter(request -> request.getState() == AccountWithdrawalRequest.State.IN_PROGRESS).isEmpty()) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        // Bulk deletion does not trigger Hibernate's per-entity embedding listener.
        outbox.beforeWithdrawalLedgerEntries(userId);
        entities.createQuery("delete from LedgerEntryChangeHistory history where history.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
        entities.createQuery("delete from LedgerEntry entry where entry.owner.id = :userId")
                .setParameter("userId", userId).executeUpdate();
    }
}

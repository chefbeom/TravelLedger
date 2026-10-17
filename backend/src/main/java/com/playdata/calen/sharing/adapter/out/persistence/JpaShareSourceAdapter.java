package com.playdata.calen.sharing.adapter.out.persistence;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.sharing.adapter.support.RecordShareContractMapper;
import com.playdata.calen.sharing.application.port.out.ShareSourcePort;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Content;
import com.playdata.calen.sharing.domain.model.ShareCreationData.LedgerDraft;
import com.playdata.calen.travel.domain.TravelPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class JpaShareSourceAdapter implements ShareSourcePort {
    private final EntityManager entityManager;
    private final LedgerEntryService ledger;
    private final ShareSnapshotCodec codec;

    @Override public Content lockOwnedSource(Long userId, RecordShareKind kind, Long sourceId, LedgerDraft override) {
        Object source = kind == RecordShareKind.LEDGER
                ? entityManager.find(LedgerEntry.class, sourceId, LockModeType.PESSIMISTIC_WRITE)
                : entityManager.find(TravelPlan.class, sourceId, LockModeType.PESSIMISTIC_WRITE);
        if (source instanceof LedgerEntry entry && entry.getOwner().getId().equals(userId) && entry.getDeletedAt() == null) {
            var response = override == null ? ledger.getEntryForSharing(userId, sourceId)
                    : ledger.prepareEntryForSharing(userId, sourceId, RecordShareContractMapper.ledgerRequest(override));
            var snapshot = RecordShareContractMapper.snapshot(response);
            return new Content(response.title(), snapshot, codec.fingerprint(codec.encode(snapshot)));
        }
        if (source instanceof TravelPlan plan && plan.getOwner().getId().equals(userId)) return new Content(plan.getName(), null, null);
        throw new NotFoundException("공유할 본인 기록을 찾을 수 없습니다.");
    }
}

package com.playdata.calen.ledger.ai;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers new histories under the account lock used by withdrawal. */
@Service
@RequiredArgsConstructor
public class LedgerAiAnalysisHistoryWriter {
    private final AppUserRepository users;
    private final LedgerAiAnalysisHistoryRepository histories;

    @Transactional
    public LedgerAiAnalysisHistory create(Long ownerId, LedgerAiAnalysisHistory draft) {
        var owner = users.findActiveForPreferenceUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
        draft.setOwner(owner);
        return histories.saveAndFlush(draft);
    }
}

package com.playdata.calen.ledger.ocr;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates an image analysis history only while its account is active and locked. */
@Service
@RequiredArgsConstructor
public class LedgerImageAnalysisHistoryWriter {
    private final AppUserRepository users;
    private final LedgerImageAnalysisRequestRepository histories;

    @Transactional
    public LedgerImageAnalysisRequest create(Long ownerId, LedgerImageAnalysisRequest draft) {
        var owner = users.findActiveForPreferenceUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
        draft.setOwner(owner);
        return histories.saveAndFlush(draft);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restart(Long ownerId, Long historyId, Long version, String documentType, String prompt, LocalDateTime now) {
        users.findActiveForPreferenceUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
        return histories.restart(historyId, ownerId, version, documentType, prompt, now);
    }
}

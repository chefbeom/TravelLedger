package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.ledger.application.port.out.LedgerAnalysisWorkStore;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JpaLedgerAnalysisWorkStore implements LedgerAnalysisWorkStore {
    private final LedgerAiAnalysisHistoryRepository histories;
    @Override public String status(Long owner, Long history) {
        var row = histories.findByIdAndOwnerId(history, owner).orElse(null);
        return row == null || row.getStatus() == null ? null : row.getStatus().name();
    }
    @Override public int savePayload(Long owner, Long history, String payload) { return histories.saveProcessingPayload(history, owner, payload); }
}

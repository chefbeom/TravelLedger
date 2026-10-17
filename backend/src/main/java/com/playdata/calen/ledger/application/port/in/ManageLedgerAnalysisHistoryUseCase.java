package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import java.util.Optional;

public interface ManageLedgerAnalysisHistoryUseCase {
    HistoryPage search(Long owner, Filter filter);
    Detail detail(Long owner, Long id);
    int delete(Long owner, Long id);
    int deleteAll(Long owner);
    /** Internal matching calls preserve the already-validated caller and its transaction context. */
    Optional<StoredHistory> completed(MatchKey key);
    Optional<StoredHistory> processing(MatchKey key);
    AnalysisResult readStored(StoredHistory history);
    AnalysisResult readReusable(StoredHistory history);
}

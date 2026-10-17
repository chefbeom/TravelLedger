package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import java.util.Optional;

public interface LedgerAnalysisHistoryStore {
    void requireActiveOwner(Long owner);
    HistoryPage search(Long owner, Search filter);
    Optional<StoredHistory> find(Long owner, Long id);
    int delete(Long owner, Long id);
    int deleteAll(Long owner);
    Optional<StoredHistory> completed(MatchKey key);
    Optional<StoredHistory> processing(MatchKey key);
}

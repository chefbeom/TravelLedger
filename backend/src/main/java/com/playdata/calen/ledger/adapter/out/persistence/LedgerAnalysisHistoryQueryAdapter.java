package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisHistoryStore;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Preserve existing query predicates and per-call transaction participation; do not create a new transaction. */
@Component
@RequiredArgsConstructor
public class LedgerAnalysisHistoryQueryAdapter implements LedgerAnalysisHistoryStore {
    private final AppUserService users;
    private final LedgerAiAnalysisHistoryRepository histories;
    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public HistoryPage search(Long owner, Search filter) {
        var page = histories.searchHistories(owner, filter.mode(), filter.periodType(), filter.createdFrom(),
                filter.createdToExclusive(), filter.comparisonOnly(), PageRequest.of(filter.page(), filter.size()));
        return new HistoryPage(page.getContent().stream().map(LedgerAnalysisHistoryMapper::summary).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
    @Override public Optional<StoredHistory> find(Long owner, Long id) {
        return histories.findByIdAndOwnerId(id, owner).map(LedgerAnalysisHistoryMapper::stored);
    }
    @Override public int delete(Long owner, Long id) { return histories.deleteByIdAndOwnerId(id, owner); }
    @Override public int deleteAll(Long owner) { return histories.deleteAllByOwnerId(owner); }
    @Override public Optional<StoredHistory> completed(MatchKey key) {
        return histories.findLatestMatchingCompletedAnalysis(key.owner(), LedgerAiAnalysisStatus.COMPLETED, key.provider(),
                key.model(), key.mode(), key.periodType(), key.from(), key.to(), key.compareFrom(), key.compareTo(),
                key.createdAfter(), Limit.of(1)).map(LedgerAnalysisHistoryMapper::stored);
    }
    @Override public Optional<StoredHistory> processing(MatchKey key) {
        return histories.findLatestMatchingProcessingAnalysis(key.owner(), LedgerAiAnalysisStatus.PROCESSING, key.provider(),
                key.model(), key.mode(), key.periodType(), key.from(), key.to(), key.compareFrom(), key.compareTo(),
                key.createdAfter(), Limit.of(1)).map(LedgerAnalysisHistoryMapper::stored);
    }
}

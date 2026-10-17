package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisDatasetStore;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.DateRange;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.service.StatisticsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** No new transaction: retain StatisticsService and the original repository query boundaries. */
@Component
@RequiredArgsConstructor
public class LedgerAnalysisDatasetQueryAdapter implements LedgerAnalysisDatasetStore {
    private final StatisticsService statistics;
    private final LedgerEntryRepository entries;
    @Override public Overview overview(Long owner, DateRange range) { return LedgerAnalysisPreparationMapper.overview(statistics.getOverview(owner, range.from(), range.to())); }
    @Override public List<CategoryBreakdown> categories(Long owner, DateRange range) { return LedgerAnalysisPreparationMapper.categoryFacts(statistics.getCategoryBreakdown(owner, range.from(), range.to(), EntryType.EXPENSE)); }
    @Override public List<PaymentBreakdown> payments(Long owner, DateRange range) { return LedgerAnalysisPreparationMapper.paymentFacts(statistics.getPaymentBreakdown(owner, range.from(), range.to())); }
    @Override public List<PeriodComparison> periods(Long owner, LocalDate anchor, ComparisonUnit unit, int count) { return LedgerAnalysisPreparationMapper.periodFacts(statistics.compare(owner, anchor, unit, count)); }
    @Override public List<ExpenseEntryPayload> expenses(Long owner, DateRange range) { return entries.findExpenseEntriesForAiAnalysis(owner, range.from(), range.to(), EntryType.EXPENSE).stream().map(this::expense).toList(); }
    @Override public List<ExpenseEntryPayload> topExpenses(Long owner, DateRange range, int limit) { return entries.findTopExpenseEntriesForAiAnalysis(owner, range.from(), range.to(), EntryType.EXPENSE, PageRequest.of(0, limit)).stream().map(this::expense).toList(); }
    private ExpenseEntryPayload expense(LedgerEntryRepository.AiExpenseEntryAggregate r) {
        return new ExpenseEntryPayload(r.getEntryDate(), text(r.getTitle()), text(r.getMemo()), number(r.getAmount()),
                text(r.getCategoryGroupName()), hasText(r.getCategoryDetailName()) ? r.getCategoryDetailName() : "Uncategorized", text(r.getPaymentMethodName()));
    }
    private String text(String value) { return value == null ? "" : value; }
    private BigDecimal number(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}

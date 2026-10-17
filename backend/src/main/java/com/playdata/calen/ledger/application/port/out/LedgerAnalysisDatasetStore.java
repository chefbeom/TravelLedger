package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.ComparisonUnit;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.DateRange;
import java.time.LocalDate;
import java.util.List;

public interface LedgerAnalysisDatasetStore {
    Overview overview(Long owner, DateRange range);
    List<CategoryBreakdown> categories(Long owner, DateRange range);
    List<PaymentBreakdown> payments(Long owner, DateRange range);
    List<PeriodComparison> periods(Long owner, LocalDate anchor, ComparisonUnit unit, int count);
    List<ExpenseEntryPayload> expenses(Long owner, DateRange range);
    List<ExpenseEntryPayload> topExpenses(Long owner, DateRange range, int limit);
}

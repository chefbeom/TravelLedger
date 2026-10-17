package com.playdata.calen.ledger.application.service;

import com.playdata.calen.ledger.application.port.in.PrepareLedgerAnalysisDatasetUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisDatasetStore;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy;
import java.util.List;

/** No new encompassing transaction: existing statistics/query boundaries remain in the store adapter. */
public final class PrepareLedgerAnalysisDatasetService implements PrepareLedgerAnalysisDatasetUseCase {
    private static final int TOP_EXPENSE_LIMIT = 20;
    private final LedgerAnalysisDatasetStore store;
    public PrepareLedgerAnalysisDatasetService(LedgerAnalysisDatasetStore store) { this.store = store; }
    @Override public Dataset prepare(Long owner, AnalysisPlan plan) {
        var primary = plan.primaryRange(); var comparison = plan.comparisonRange();
        var overview = store.overview(owner, primary);
        var categories = store.categories(owner, primary);
        var payments = store.payments(owner, primary);
        var periods = store.periods(owner, primary.to(), LedgerAnalysisCalendarPolicy.comparisonUnitFor(plan.periodType()), LedgerAnalysisCalendarPolicy.comparisonPeriodsFor(plan.periodType()));
        var expenses = store.expenses(owner, primary);
        var top = plan.comparisonPreset() == LedgerAiComparisonPreset.WEEKLY_DIGEST ? List.<ExpenseEntryPayload>of() : store.topExpenses(owner, primary, TOP_EXPENSE_LIMIT);
        Overview comparisonOverview = null;
        List<CategoryBreakdown> comparisonCategories = List.of(); List<PaymentBreakdown> comparisonPayments = List.of(); List<ExpenseEntryPayload> comparisonExpenses = List.of();
        if (comparison != null) {
            comparisonOverview = store.overview(owner, comparison);
            comparisonCategories = store.categories(owner, comparison);
            comparisonPayments = store.payments(owner, comparison);
            comparisonExpenses = store.expenses(owner, comparison);
        }
        return new Dataset(overview, categories, payments, periods, expenses, top, comparisonOverview, comparisonCategories, comparisonPayments, comparisonExpenses);
    }
}

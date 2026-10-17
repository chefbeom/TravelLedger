package com.playdata.calen.ledger.adapter.out.analysis;

import com.playdata.calen.ledger.ai.*;
import com.playdata.calen.ledger.adapter.in.ai.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.*;
import com.playdata.calen.ledger.adapter.support.*;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisWorkFormats;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.DateRange;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.Dataset;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.AnalysisResult;
import com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy;
import com.playdata.calen.ledger.dto.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared sync/async v2/API mapping. Native DTOs stay here, not in the worker core. */
@Component
@RequiredArgsConstructor
public class LedgerAnalysisFormats implements LedgerAnalysisWorkFormats {
    private static final int TOP_EXPENSE_LIMIT = 20;
    private static final int PROVIDER_EXPENSE_ENTRY_LIMIT = 200;
    private static final int PROVIDER_COMPARISON_ENTRY_LIMIT = 120;
    private static final int WEEKLY_DIGEST_PROVIDER_ENTRY_LIMIT = 20;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiAnalysisPayloadBuilder aiPayloadBuilder;
    private final LedgerAiAnalysisJsonCodec aiJsonCodec;
    private final LedgerAiAnalysisTextSanitizer aiText;
    private final LedgerAiAnalysisReportMerger aiReportMerger;
    private final LedgerLocalReportPolicy localReports = new LedgerLocalReportPolicy();

    @Override public Prepared prepare(AnalysisPlan plan, Dataset facts) {
        var payload = buildPayload(plan, dataset(facts));
        return new Prepared() {
            @Override public String serialize() { return aiJsonCodec.write(payload); }
            @Override public LedgerAnalysisData.Request request() { return LedgerAnalysisBoundaryMapper.toRequest(payload); }
        };
    }
    @Override public AnalysisResult response(Long id, AnalysisPlan plan, Dataset facts, Result remote) {
        return LedgerAnalysisHistoryMapper.facts(buildResponse(id, plan, dataset(facts), remote));
    }
    @Override public String serializeResult(AnalysisResult result) { return aiJsonCodec.write(LedgerAnalysisHistoryMapper.response(result)); }
    @Override public String failureText(RuntimeException failure) { return aiText.redactSensitiveText(failure.getMessage(), 500); }
    public static AnalysisDataset dataset(Dataset source) {
        return new AnalysisDataset(LedgerAnalysisPreparationMapper.overview(source.overview()), LedgerAnalysisPreparationMapper.categories(source.categoryBreakdown()),
                LedgerAnalysisPreparationMapper.payments(source.paymentBreakdown()), LedgerAnalysisPreparationMapper.periods(source.periodComparison()),
                LedgerAnalysisPreparationMapper.expenses(source.expenseEntries()), LedgerAnalysisPreparationMapper.expenses(source.topExpenses()),
                LedgerAnalysisPreparationMapper.overview(source.comparisonOverview()), LedgerAnalysisPreparationMapper.categories(source.comparisonCategoryBreakdown()),
                LedgerAnalysisPreparationMapper.payments(source.comparisonPaymentBreakdown()), LedgerAnalysisPreparationMapper.expenses(source.comparisonExpenseEntries()));
    }

    public LedgerAiN8nPayload buildPayload(AnalysisPlan plan, AnalysisDataset dataset) {
        boolean weeklyDigest = plan.comparisonPreset() == LedgerAiComparisonPreset.WEEKLY_DIGEST;
        List<ExpenseEntryPayload> providerExpenseEntries = weeklyDigest
                ? aiPayloadBuilder.providerWeeklyDigestEntries(dataset.expenseEntries(), WEEKLY_DIGEST_PROVIDER_ENTRY_LIMIT)
                : aiPayloadBuilder.providerExpenseEntries(dataset.expenseEntries(), PROVIDER_EXPENSE_ENTRY_LIMIT);
        List<ExpenseEntryPayload> providerTopExpenses = weeklyDigest
                ? List.of()
                : aiPayloadBuilder.providerExpenseEntries(dataset.topExpenses(), TOP_EXPENSE_LIMIT);
        List<ExpenseEntryPayload> providerComparisonExpenseEntries = weeklyDigest
                ? aiPayloadBuilder.providerWeeklyDigestEntries(dataset.comparisonExpenseEntries(), WEEKLY_DIGEST_PROVIDER_ENTRY_LIMIT)
                : aiPayloadBuilder.providerExpenseEntries(dataset.comparisonExpenseEntries(), PROVIDER_COMPARISON_ENTRY_LIMIT);
        List<RecurringExpenseCandidatePayload> providerRecurringCandidates = weeklyDigest
                ? List.of()
                : aiPayloadBuilder.providerRecurringCandidates(buildRecurringExpenseCandidates(dataset.expenseEntries()));

        return new LedgerAiN8nPayload(
                "travelledger.ledger-ai-analysis.v2",
                "ko",
                properties.getModel(),
                plan.mode(),
                plan.periodType(),
                plan.comparisonPreset(),
                plan.primaryRange().from(),
                plan.primaryRange().to(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(),
                dataset.overview(),
                dataset.categoryBreakdown(),
                dataset.paymentBreakdown(),
                buildExpensePaymentBreakdown(dataset.expenseEntries()),
                dataset.periodComparison(),
                providerExpenseEntries,
                providerTopExpenses,
                providerRecurringCandidates,
                dataset.comparisonOverview(),
                dataset.comparisonCategoryBreakdown(),
                dataset.comparisonPaymentBreakdown(),
                providerComparisonExpenseEntries,
                plan.focusPrompt(),
                aiPayloadBuilder.buildPayloadMinimizationSummary(dataset.expenseEntries().size(), dataset.comparisonExpenseEntries().size(), providerExpenseEntries, providerComparisonExpenseEntries),
                LedgerAiOutputContract.text()
        );
    }

    public LedgerAiAnalysisResponse buildResponse(Long historyId, AnalysisPlan plan, AnalysisDataset dataset, Result remote) {
        DateRange primary = plan.primaryRange();
        DateRange comparison = plan.comparisonRange();
        LedgerAiAnalysisReportResponse report = buildReport(plan, dataset, remote);
        return new LedgerAiAnalysisResponse(
                historyId,
                plan.mode(),
                plan.periodType(),
                plan.comparisonPreset(),
                primary.from(),
                primary.to(),
                comparison == null ? null : comparison.from(),
                comparison == null ? null : comparison.to(),
                properties.getModel(),
                Instant.now(),
                dataset.overview().expense(),
                averageDailyExpense(dataset.overview().expense(), primary),
                dataset.expenseEntries().size(),
                dataset.comparisonOverview() == null ? BigDecimal.ZERO : dataset.comparisonOverview().expense(),
                dataset.categoryBreakdown(),
                dataset.paymentBreakdown(),
                dataset.periodComparison(),
                report,
                firstNonBlank(remote.summary(), report.keySummary()),
                firstNonEmpty(remote.highlights(), report.notableSpending()),
                firstNonEmpty(firstNonEmpty(remote.warnings(), remote.risks()), report.abnormalSpending()),
                firstNonEmpty(remote.recommendations(), report.improvementActions()),
                remote.categoryInsights(),
                firstNonEmpty(remote.paymentInsights(), singleItemList(report.topPaymentMethod())),
                firstNonEmpty(remote.trendInsights(), report.comparisonFocus()),
                firstNonEmpty(remote.unusualSpendingInsights(), report.abnormalSpending()),
                firstNonEmpty(remote.fixedCostInsights(), concatLists(report.regularSpending(), report.fixedExpenses(), report.subscriptions())),
                remote.nextPeriodForecast(),
                remote.habitAssessment()
        );
    }

    private BigDecimal averageDailyExpense(BigDecimal expense, DateRange range) {
        return localReports.averageDailyExpense(expense, LedgerLocalReportMapper.range(range.from(), range.to()));
    }

    private List<RecurringExpenseCandidatePayload> buildRecurringExpenseCandidates(List<ExpenseEntryPayload> entries) {
        return LedgerLocalReportMapper.recurring(localReports.buildRecurringExpenseCandidates(LedgerLocalReportMapper.expenses(entries)));
    }

    private LedgerAiAnalysisReportResponse buildReport(AnalysisPlan plan, AnalysisDataset dataset, Result remote) {
        return aiReportMerger.merge(buildFallbackReport(plan, dataset), remote);
    }

    private LedgerAiAnalysisReportResponse buildFallbackReport(AnalysisPlan plan, AnalysisDataset dataset) {
        var compare = plan.comparisonRange();
        var reportPlan = LedgerLocalReportMapper.plan(plan.mode() == LedgerAiAnalysisMode.COMPARISON, plan.primaryRange().from(), plan.primaryRange().to(),
                compare == null ? null : LedgerLocalReportMapper.range(compare.from(), compare.to()));
        var reportData = LedgerLocalReportMapper.dataset(dataset.overview(), dataset.categoryBreakdown(), dataset.expenseEntries(), dataset.topExpenses(),
                dataset.comparisonOverview(), dataset.comparisonCategoryBreakdown(), dataset.comparisonExpenseEntries());
        return LedgerLocalReportMapper.response(localReports.buildFallbackReport(reportPlan, reportData));
    }

    private List<PaymentSummaryPayload> buildExpensePaymentBreakdown(List<ExpenseEntryPayload> entries) {
        return LedgerLocalReportMapper.payments(localReports.buildExpensePaymentBreakdown(LedgerLocalReportMapper.expenses(entries)));
    }

    private List<String> concatLists(List<String> first, List<String> second, List<String> third) {
        List<String> result = new ArrayList<>();
        result.addAll(firstNonEmpty(first, List.of()));
        result.addAll(firstNonEmpty(second, List.of()));
        result.addAll(firstNonEmpty(third, List.of()));
        return result;
    }

    private List<String> singleItemList(String value) {
        return aiText.hasText(value) ? List.of(value) : List.of();
    }

    private String firstNonBlank(String primary, String fallback) {
        return aiText.hasText(primary) ? primary : aiText.safeText(fallback);
    }

    private List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record AnalysisDataset(
            OverviewResponse overview,
            List<CategoryBreakdownItemResponse> categoryBreakdown,
            List<PaymentBreakdownItemResponse> paymentBreakdown,
            List<PeriodComparisonItemResponse> periodComparison,
            List<ExpenseEntryPayload> expenseEntries,
            List<ExpenseEntryPayload> topExpenses,
            OverviewResponse comparisonOverview,
            List<CategoryBreakdownItemResponse> comparisonCategoryBreakdown,
            List<PaymentBreakdownItemResponse> comparisonPaymentBreakdown,
            List<ExpenseEntryPayload> comparisonExpenseEntries
    ) {
    }
}

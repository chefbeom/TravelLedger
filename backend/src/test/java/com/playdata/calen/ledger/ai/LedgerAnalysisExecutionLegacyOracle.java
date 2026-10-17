package com.playdata.calen.ledger.ai;

import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.adapter.in.ai.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.*;
import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.domain.policy.*;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;

/** Frozen worker and native format bodies. It never calls the migrated worker/format adapters. */
@RequiredArgsConstructor
public final class LedgerAnalysisExecutionLegacyOracle {
    private static final int TOP_EXPENSE_LIMIT = 20;
    private static final int PROVIDER_EXPENSE_ENTRY_LIMIT = 200;
    private static final int PROVIDER_COMPARISON_ENTRY_LIMIT = 120;
    private static final int WEEKLY_DIGEST_PROVIDER_ENTRY_LIMIT = 20;
    private static final Logger log = LoggerFactory.getLogger("com.playdata.calen.ledger.ai.LedgerAiAnalysisService");
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerSpendingAnalysisPort remoteClient;
    private final LedgerAiAnalysisMetrics aiMetrics;
    private final LedgerAiAnalysisJsonCodec aiJsonCodec;
    private final LedgerAiAnalysisTextSanitizer aiText;
    private final LedgerAiAnalysisPayloadBuilder aiPayloadBuilder;
    private final LedgerAiAnalysisReportMerger aiReportMerger;
    private final FinalizeLedgerAiJobUseCase finalizer;
    private final BiFunction<Long, AnalysisPlan, AnalysisDataset> datasets;
    private final LedgerLocalReportPolicy localReports = new LedgerLocalReportPolicy();
    public record DateRange(LocalDate from, LocalDate to) {
    }
    public record AnalysisPlan(
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset,
            DateRange primaryRange,
            DateRange comparisonRange,
            String focusPrompt
    ) {
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
    public void processAnalysisInBackground(Long userId, Long historyId, AnalysisPlan plan,
            com.playdata.calen.common.jobs.WorkLeaseService.Lease lease) {
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId).orElse(null);
        if (history == null || !LedgerAiJobLifecyclePolicy.isProcessing(history.getStatus() == null ? null : history.getStatus().name())) {
            return;
        }

        AnalysisDataset dataset = null;
        LedgerAiN8nPayload payload = null;
        Timer.Sample aiRequestTimer = null;
        boolean aiRequestRecorded = false;
        try {
            dataset = buildDataset(userId, plan);
            payload = buildPayload(plan, dataset);
            if (historyRepository.saveProcessingPayload(historyId, userId, aiJsonCodec.write(payload)) == 0) return;

            aiRequestTimer = aiMetrics.startAiRequestTimer();
            Result remote = remoteClient.analyze(LedgerAnalysisBoundaryMapper.toRequest(payload));
            if (lease != null && !lease.isValid()) return;
            LedgerAiAnalysisResponse response = buildResponse(historyId, plan, dataset, remote);
            boolean completed = finalizer.complete(new Completion(new AnalysisJob(userId, historyId),
                    aiText.safeText(remote.summary()), aiJsonCodec.write(response), null, LocalDateTime.now()));
            aiMetrics.recordAiRequest(aiRequestTimer, completed ? "success" : "discarded");
            aiRequestRecorded = true;
        } catch (RuntimeException exception) {
            if (lease != null && !lease.isValid()) return;
            if (aiRequestTimer != null && !aiRequestRecorded) {
                aiMetrics.recordAiRequest(aiRequestTimer, "failure");
            }
            // Never reuse the pre-inference entity here: its version may already have changed.
            markAnalysisFailed(userId, historyId, exception);
            log.warn("Ledger AI analysis failed: historyId={}, failureType={}", historyId, exception.getClass().getSimpleName());
        }
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

    public BigDecimal averageDailyExpense(BigDecimal expense, DateRange range) {
        return localReports.averageDailyExpense(expense, LedgerLocalReportMapper.range(range.from(), range.to()));
    }

    public List<RecurringExpenseCandidatePayload> buildRecurringExpenseCandidates(List<ExpenseEntryPayload> entries) {
        return LedgerLocalReportMapper.recurring(localReports.buildRecurringExpenseCandidates(LedgerLocalReportMapper.expenses(entries)));
    }

    public LedgerAiAnalysisReportResponse buildReport(AnalysisPlan plan, AnalysisDataset dataset, Result remote) {
        return aiReportMerger.merge(buildFallbackReport(plan, dataset), remote);
    }

    public LedgerAiAnalysisReportResponse buildFallbackReport(AnalysisPlan plan, AnalysisDataset dataset) {
        var compare = plan.comparisonRange();
        var reportPlan = LedgerLocalReportMapper.plan(plan.mode() == LedgerAiAnalysisMode.COMPARISON, plan.primaryRange().from(), plan.primaryRange().to(),
                compare == null ? null : LedgerLocalReportMapper.range(compare.from(), compare.to()));
        var reportData = LedgerLocalReportMapper.dataset(dataset.overview(), dataset.categoryBreakdown(), dataset.expenseEntries(), dataset.topExpenses(),
                dataset.comparisonOverview(), dataset.comparisonCategoryBreakdown(), dataset.comparisonExpenseEntries());
        return LedgerLocalReportMapper.response(localReports.buildFallbackReport(reportPlan, reportData));
    }

    public List<PaymentSummaryPayload> buildExpensePaymentBreakdown(List<ExpenseEntryPayload> entries) {
        return LedgerLocalReportMapper.payments(localReports.buildExpensePaymentBreakdown(LedgerLocalReportMapper.expenses(entries)));
    }

    public List<String> concatLists(List<String> first, List<String> second, List<String> third) {
        List<String> result = new ArrayList<>();
        result.addAll(firstNonEmpty(first, List.of()));
        result.addAll(firstNonEmpty(second, List.of()));
        result.addAll(firstNonEmpty(third, List.of()));
        return result;
    }

    public List<String> singleItemList(String value) {
        return aiText.hasText(value) ? List.of(value) : List.of();
    }

    public String firstNonBlank(String primary, String fallback) {
        return aiText.hasText(primary) ? primary : aiText.safeText(fallback);
    }

    public List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    public BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private AnalysisDataset buildDataset(Long owner, AnalysisPlan plan) { return datasets.apply(owner, plan); }
    private void markAnalysisFailed(Long owner, Long id, RuntimeException failure) {
        finalizer.fail(new Failure(new AnalysisJob(owner, id), aiText.redactSensitiveText(failure.getMessage(), 500), "AI analysis failed.", LocalDateTime.now()));
    }
}

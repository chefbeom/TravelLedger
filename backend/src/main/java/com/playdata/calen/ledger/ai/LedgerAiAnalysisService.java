package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.ComparisonUnit;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.LedgerAiComparisonPreset;
import com.playdata.calen.ledger.dto.CategoryBreakdownItemResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDeleteResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDetailResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryPageResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistorySummaryResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse;
import com.playdata.calen.ledger.dto.PeriodComparisonItemResponse;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.service.StatisticsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerAiAnalysisService {

    private static final int TOP_EXPENSE_LIMIT = 20;
    private static final int PROVIDER_EXPENSE_ENTRY_LIMIT = 200;
    private static final int PROVIDER_COMPARISON_ENTRY_LIMIT = 120;
    private static final int PROVIDER_TEXT_LIMIT = 80;
    private static final int PROVIDER_MEMO_LIMIT = 160;
    private static final int MAX_HISTORY_PAGE_SIZE = 50;
    private static final long MAX_CUSTOM_RANGE_DAYS = 366;
    private static final Duration DUPLICATE_SUPPRESSION_WINDOW = Duration.ofMinutes(5);

    private final AppUserService appUserService;
    private final StatisticsService statisticsService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiRemoteClient remoteClient;
    private final ObjectMapper objectMapper;
    private final UserNotificationService userNotificationService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public LedgerAiAnalysisStatusResponse getStatus() {
        return new LedgerAiAnalysisStatusResponse(
                properties.isEnabled(),
                properties.isConfigured(),
                properties.getProvider(),
                properties.isWorkflowConfigured(),
                properties.isApiKeyConfigured(),
                properties.isLmStudioConfigured(),
                properties.getModel(),
                properties.statusMessage()
        );
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public LedgerAiAnalysisResponse analyze(Long userId, LedgerAiAnalysisRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        if (!properties.isConfigured()) {
            throw new BadRequestException(properties.statusMessage());
        }

        AnalysisPlan plan = resolvePlan(request);
        LedgerAiAnalysisResponse reusableResponse = findReusableAnalysis(userId, plan);
        if (reusableResponse != null) {
            return reusableResponse;
        }

        AnalysisDataset dataset = buildDataset(userId, plan);
        LedgerAiN8nPayload payload = buildPayload(plan, dataset);
        Timer.Sample aiRequestTimer = aiMetrics.startAiRequestTimer();
        boolean aiRequestRecorded = false;

        try {
            LedgerAiRemoteResponse remote = remoteClient.analyze(payload);
            aiMetrics.recordAiRequest(aiRequestTimer, "success");
            aiRequestRecorded = true;
            LedgerAiAnalysisHistory history = baseHistory(owner, plan);
            history.setStatus(LedgerAiAnalysisStatus.COMPLETED);
            history.setSummary(safeText(remote.summary()));
            history.setRequestPayloadJson(writeJson(payload));
            history = historyRepository.save(history);

            LedgerAiAnalysisResponse response = buildResponse(history.getId(), plan, dataset, remote);
            history.setResultJson(writeJson(response));
            return response;
        } catch (RuntimeException exception) {
            if (!aiRequestRecorded) {
                aiMetrics.recordAiRequest(aiRequestTimer, "failure");
            }
            LedgerAiAnalysisHistory failedHistory = baseHistory(owner, plan);
            failedHistory.setStatus(LedgerAiAnalysisStatus.FAILED);
            failedHistory.setSummary("AI ???怨쀫뮝力??????거????????怨뚯댅");
            failedHistory.setErrorMessage(sanitizeProviderErrorMessage(exception.getMessage()));
            failedHistory.setRequestPayloadJson(writeJson(payload));
            historyRepository.save(failedHistory);
            throw exception;
        }
    }

    private void notifyAiAnalysisCompleted(Long userId, LedgerAiAnalysisHistory history) {
        notifyAiAnalysis(
                userId,
                history,
                "AI_ANALYSIS_DONE",
                "AI analysis completed",
                "Your ledger AI analysis is ready."
        );
    }

    private void notifyAiAnalysisFailed(Long userId, LedgerAiAnalysisHistory history) {
        notifyAiAnalysis(
                userId,
                history,
                "AI_OR_OCR_FAILED",
                "AI analysis failed",
                "Your ledger AI analysis could not be completed. Please review the AI status and retry."
        );
    }

    private void notifyAiAnalysis(
            Long userId,
            LedgerAiAnalysisHistory history,
            String type,
            String title,
            String message
    ) {
        try {
            userNotificationService.createSystemNotification(
                    userId,
                    type,
                    title,
                    message,
                    "/statistics?aiAnalysisHistoryId=" + history.getId(),
                    "{\"historyId\":" + history.getId() + ",\"status\":\"" + history.getStatus().name() + "\"}"
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create ledger AI notification: historyId={}, type={}", history.getId(), type, exception);
        }
    }
    public LedgerAiAnalysisHistoryPageResponse getHistories(
            Long userId,
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LocalDate createdFrom,
            LocalDate createdTo,
            Boolean comparisonOnly,
            int page,
            int size
    ) {
        appUserService.getRequiredUser(userId);
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BadRequestException("???怨쀫뮝力???????????덉땃???β뼯援??????癲ル슢??節녿쨨??? ?????살꺎??????살퓚??????????????욱룏???????낆젵.");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_HISTORY_PAGE_SIZE));
        PageRequest pageRequest = PageRequest.of(safePage, safeSize);
        LocalDateTime createdFromDateTime = createdFrom == null ? null : createdFrom.atStartOfDay();
        LocalDateTime createdToExclusive = createdTo == null ? null : createdTo.plusDays(1).atStartOfDay();
        Page<LedgerAiAnalysisHistory> resultPage = historyRepository.searchHistories(
                userId,
                mode,
                periodType,
                createdFromDateTime,
                createdToExclusive,
                comparisonOnly,
                pageRequest
        );

        return new LedgerAiAnalysisHistoryPageResponse(
                resultPage.getContent().stream().map(this::toSummary).toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );
    }

    public LedgerAiAnalysisHistoryDetailResponse getHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("AI ???怨쀫뮝力???????????덉땃???轅붽틓?????????????욱룏???????낆젵."));
        LedgerAiAnalysisResponse result = readResult(history.getResultJson());
        return new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), result);
    }

    @Transactional
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        int deletedCount = historyRepository.deleteByIdAndOwnerId(historyId, userId);
        if (deletedCount == 0) {
            throw new NotFoundException("AI analysis history was not found.");
        }
        return new LedgerAiAnalysisHistoryDeleteResponse(deletedCount);
    }

    @Transactional
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistories(Long userId) {
        appUserService.getRequiredUser(userId);
        return new LedgerAiAnalysisHistoryDeleteResponse(historyRepository.deleteAllByOwnerId(userId));
    }
    @Transactional(noRollbackFor = RuntimeException.class)
    public LedgerAiAnalysisResponse rerun(Long userId, Long historyId) {
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("AI ???怨쀫뮝力???????????덉땃???轅붽틓?????????????욱룏???????낆젵."));
        return analyze(userId, new LedgerAiAnalysisRequest(
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getToDate(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate()
        ));
    }

    public LedgerAiAnalysisHistoryDetailResponse getLatestMatching(Long userId, LedgerAiAnalysisRequest request) {
        appUserService.getRequiredUser(userId);
        AnalysisPlan plan = resolvePlan(request);
        return findLatestMatchingAnalysis(userId, plan, null)
                .map(history -> new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), readResult(history.getResultJson())))
                .orElse(null);
    }

    private LedgerAiAnalysisResponse findReusableAnalysis(Long userId, AnalysisPlan plan) {
        LocalDateTime createdAfter = LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW);
        return findLatestMatchingAnalysis(userId, plan, createdAfter)
                .map(history -> readResult(history.getResultJson()))
                .orElse(null);
    }

    private java.util.Optional<LedgerAiAnalysisHistory> findLatestMatchingAnalysis(Long userId, AnalysisPlan plan, LocalDateTime createdAfter) {
        return historyRepository.findLatestMatchingCompletedAnalysis(
                userId,
                LedgerAiAnalysisStatus.COMPLETED,
                aiMetrics.providerLabel(),
                properties.getModel(),
                plan.mode(),
                plan.periodType(),
                plan.primaryRange().from(),
                plan.primaryRange().to(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(),
                createdAfter
        );
    }

    private AnalysisDataset buildDataset(Long userId, AnalysisPlan plan) {
        DateRange primary = plan.primaryRange();
        DateRange comparison = plan.comparisonRange();
        OverviewResponse overview = statisticsService.getOverview(userId, primary.from(), primary.to());
        List<CategoryBreakdownItemResponse> categoryBreakdown = statisticsService.getCategoryBreakdown(userId, primary.from(), primary.to(), EntryType.EXPENSE);
        List<PaymentBreakdownItemResponse> paymentBreakdown = statisticsService.getPaymentBreakdown(userId, primary.from(), primary.to());
        List<PeriodComparisonItemResponse> periodComparison = statisticsService.compare(
                userId,
                primary.to(),
                comparisonUnitFor(plan.periodType()),
                comparisonPeriodsFor(plan.periodType())
        );
        List<ExpenseEntryPayload> expenseEntries = ledgerEntryRepository.findExpenseEntriesForAiAnalysis(
                        userId,
                        primary.from(),
                        primary.to(),
                        EntryType.EXPENSE
                ).stream()
                .map(this::toExpensePayload)
                .toList();
        List<ExpenseEntryPayload> topExpenses = ledgerEntryRepository.findTopExpenseEntriesForAiAnalysis(
                        userId,
                        primary.from(),
                        primary.to(),
                        EntryType.EXPENSE,
                        PageRequest.of(0, TOP_EXPENSE_LIMIT)
                ).stream()
                .map(this::toExpensePayload)
                .toList();

        OverviewResponse comparisonOverview = null;
        List<CategoryBreakdownItemResponse> comparisonCategoryBreakdown = List.of();
        List<PaymentBreakdownItemResponse> comparisonPaymentBreakdown = List.of();
        List<ExpenseEntryPayload> comparisonExpenseEntries = List.of();
        if (comparison != null) {
            comparisonOverview = statisticsService.getOverview(userId, comparison.from(), comparison.to());
            comparisonCategoryBreakdown = statisticsService.getCategoryBreakdown(userId, comparison.from(), comparison.to(), EntryType.EXPENSE);
            comparisonPaymentBreakdown = statisticsService.getPaymentBreakdown(userId, comparison.from(), comparison.to());
            comparisonExpenseEntries = ledgerEntryRepository.findExpenseEntriesForAiAnalysis(
                            userId,
                            comparison.from(),
                            comparison.to(),
                            EntryType.EXPENSE
                    ).stream()
                    .map(this::toExpensePayload)
                    .toList();
        }

        return new AnalysisDataset(
                overview,
                categoryBreakdown,
                paymentBreakdown,
                periodComparison,
                expenseEntries,
                topExpenses,
                comparisonOverview,
                comparisonCategoryBreakdown,
                comparisonPaymentBreakdown,
                comparisonExpenseEntries
        );
    }

    private LedgerAiN8nPayload buildPayload(AnalysisPlan plan, AnalysisDataset dataset) {
        List<ExpenseEntryPayload> providerExpenseEntries = providerExpenseEntries(dataset.expenseEntries(), PROVIDER_EXPENSE_ENTRY_LIMIT);
        List<ExpenseEntryPayload> providerTopExpenses = providerExpenseEntries(dataset.topExpenses(), TOP_EXPENSE_LIMIT);
        List<ExpenseEntryPayload> providerComparisonExpenseEntries = providerExpenseEntries(dataset.comparisonExpenseEntries(), PROVIDER_COMPARISON_ENTRY_LIMIT);
        List<RecurringExpenseCandidatePayload> providerRecurringCandidates = providerRecurringCandidates(buildRecurringExpenseCandidates(dataset.expenseEntries()));

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
                buildPayloadMinimizationSummary(dataset, providerExpenseEntries, providerComparisonExpenseEntries),
                LedgerAiOutputContract.text()
        );
    }

    private PayloadMinimizationSummary buildPayloadMinimizationSummary(
            AnalysisDataset dataset,
            List<ExpenseEntryPayload> providerExpenseEntries,
            List<ExpenseEntryPayload> providerComparisonExpenseEntries
    ) {
        int expenseTotal = dataset.expenseEntries().size();
        int comparisonTotal = dataset.comparisonExpenseEntries().size();
        return new PayloadMinimizationSummary(
                expenseTotal,
                providerExpenseEntries.size(),
                Math.max(0, expenseTotal - providerExpenseEntries.size()),
                comparisonTotal,
                providerComparisonExpenseEntries.size(),
                Math.max(0, comparisonTotal - providerComparisonExpenseEntries.size()),
                PROVIDER_TEXT_LIMIT,
                PROVIDER_MEMO_LIMIT
        );
    }

    private List<ExpenseEntryPayload> providerExpenseEntries(List<ExpenseEntryPayload> entries, int limit) {
        return entries.stream()
                .limit(limit)
                .map(this::sanitizeProviderExpenseEntry)
                .toList();
    }

    private ExpenseEntryPayload sanitizeProviderExpenseEntry(ExpenseEntryPayload entry) {
        return new ExpenseEntryPayload(
                entry.entryDate(),
                limitText(entry.title(), PROVIDER_TEXT_LIMIT),
                limitText(entry.memo(), PROVIDER_MEMO_LIMIT),
                entry.amount(),
                limitText(entry.categoryGroupName(), PROVIDER_TEXT_LIMIT),
                limitText(entry.categoryDetailName(), PROVIDER_TEXT_LIMIT),
                limitText(entry.paymentMethodName(), PROVIDER_TEXT_LIMIT)
        );
    }

    private List<RecurringExpenseCandidatePayload> providerRecurringCandidates(List<RecurringExpenseCandidatePayload> candidates) {
        return candidates.stream()
                .map(candidate -> new RecurringExpenseCandidatePayload(
                        limitText(candidate.title(), PROVIDER_TEXT_LIMIT),
                        limitText(candidate.categoryGroupName(), PROVIDER_TEXT_LIMIT),
                        limitText(candidate.categoryDetailName(), PROVIDER_TEXT_LIMIT),
                        limitText(candidate.paymentMethodName(), PROVIDER_TEXT_LIMIT),
                        candidate.occurrenceCount(),
                        candidate.totalAmount(),
                        candidate.averageAmount(),
                        candidate.firstDate(),
                        candidate.lastDate(),
                        candidate.dates()
                ))
                .toList();
    }

    private LedgerAiAnalysisResponse buildResponse(Long historyId, AnalysisPlan plan, AnalysisDataset dataset, LedgerAiRemoteResponse remote) {
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
    private AnalysisPlan resolvePlan(LedgerAiAnalysisRequest request) {
        LedgerAiAnalysisMode mode = request.mode();
        if (mode == null) {
            throw new BadRequestException("AI \uBD84\uC11D \uB370\uC774\uD130\uB97C JSON\uC73C\uB85C \uC800\uC7A5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
        }
        LocalDate anchor = request.anchorDate() == null ? LocalDate.now() : request.anchorDate();
        if (mode == LedgerAiAnalysisMode.COMPARISON) {
            LedgerAiComparisonPreset preset = request.comparisonPreset() == null
                    ? LedgerAiComparisonPreset.CURRENT_MONTH_VS_PREVIOUS_MONTH
                    : request.comparisonPreset();
            ComparisonRanges ranges = resolveComparisonRanges(preset, anchor, request);
            return new AnalysisPlan(mode, ranges.periodType(), preset, ranges.primary(), ranges.comparison());
        }
        LedgerAiAnalysisPeriod periodType = request.periodType() == null ? LedgerAiAnalysisPeriod.MONTH : request.periodType();
        return new AnalysisPlan(mode, periodType, null, resolvePeriodRange(periodType, anchor, request.from(), request.to()), null);
    }

    private DateRange resolvePeriodRange(LedgerAiAnalysisPeriod periodType, LocalDate anchor, LocalDate customFrom, LocalDate customTo) {
        return switch (periodType) {
            case WEEK -> {
                LocalDate start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new DateRange(start, start.plusDays(6));
            }
            case MONTH -> {
                YearMonth month = YearMonth.from(anchor);
                yield new DateRange(month.atDay(1), month.atEndOfMonth());
            }
            case QUARTER -> {
                int firstMonth = (((anchor.getMonthValue() - 1) / 3) * 3) + 1;
                LocalDate start = LocalDate.of(anchor.getYear(), firstMonth, 1);
                yield new DateRange(start, start.plusMonths(3).minusDays(1));
            }
            case HALF_YEAR -> {
                int firstMonth = anchor.getMonthValue() <= 6 ? 1 : 7;
                LocalDate start = LocalDate.of(anchor.getYear(), firstMonth, 1);
                yield new DateRange(start, start.plusMonths(6).minusDays(1));
            }
            case YEAR -> new DateRange(anchor.withDayOfYear(1), anchor.withDayOfYear(anchor.lengthOfYear()));
            case CUSTOM -> validateCustomRange(customFrom, customTo, "custom analysis period");
        };
    }

    private ComparisonRanges resolveComparisonRanges(LedgerAiComparisonPreset preset, LocalDate anchor, LedgerAiAnalysisRequest request) {
        return switch (preset) {
            case PREVIOUS_WEEK -> {
                LocalDate thisWeekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate primaryStart = thisWeekStart.minusWeeks(1);
                DateRange primary = new DateRange(primaryStart, primaryStart.plusDays(6));
                DateRange comparison = new DateRange(primaryStart.minusWeeks(1), primaryStart.minusDays(1));
                yield new ComparisonRanges(LedgerAiAnalysisPeriod.WEEK, primary, comparison);
            }
            case CURRENT_MONTH_VS_PREVIOUS_MONTH -> {
                YearMonth month = YearMonth.from(anchor);
                YearMonth previous = month.minusMonths(1);
                yield new ComparisonRanges(
                        LedgerAiAnalysisPeriod.MONTH,
                        new DateRange(month.atDay(1), month.atEndOfMonth()),
                        new DateRange(previous.atDay(1), previous.atEndOfMonth())
                );
            }
            case MONTH_VS_PREVIOUS_3_MONTHS -> {
                YearMonth month = YearMonth.from(anchor);
                YearMonth previousStart = month.minusMonths(3);
                YearMonth previousEnd = month.minusMonths(1);
                yield new ComparisonRanges(
                        LedgerAiAnalysisPeriod.MONTH,
                        new DateRange(month.atDay(1), month.atEndOfMonth()),
                        new DateRange(previousStart.atDay(1), previousEnd.atEndOfMonth())
                );
            }
            case YEAR_VS_PREVIOUS_YEAR -> {
                LocalDate currentStart = anchor.withDayOfYear(1);
                LocalDate currentEnd = anchor.withDayOfYear(anchor.lengthOfYear());
                LocalDate previous = anchor.minusYears(1);
                yield new ComparisonRanges(
                        LedgerAiAnalysisPeriod.YEAR,
                        new DateRange(currentStart, currentEnd),
                        new DateRange(previous.withDayOfYear(1), previous.withDayOfYear(previous.lengthOfYear()))
                );
            }
            case CUSTOM -> new ComparisonRanges(
                    request.periodType() == null ? LedgerAiAnalysisPeriod.CUSTOM : request.periodType(),
                    validateCustomRange(request.from(), request.to(), "primary comparison period"),
                    validateCustomRange(request.compareFrom(), request.compareTo(), "comparison period")
            );
        };
    }

    private DateRange validateCustomRange(LocalDate from, LocalDate to, String label) {
        if (from == null || to == null) {
            throw new BadRequestException(label + "???????癲ル슢??節녿쨨?????됰씭???????살꺎???嚥싲갭큔????????밸븶???癲ル슢?????");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(label + "????癲ル슢??節녿쨨??? ?????살꺎??????살퓚??????????????욱룏???????낆젵.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_CUSTOM_RANGE_DAYS) {
            throw new BadRequestException(label + "?? ?轅붽틓????彛? 1????袁ㅻ쇀??ル?????깆궔???????節떷????????????????낆젵.");
        }
        return new DateRange(from, to);
    }

    private LedgerAiAnalysisHistory baseHistory(AppUser owner, AnalysisPlan plan) {
        LedgerAiAnalysisHistory history = new LedgerAiAnalysisHistory();
        history.setOwner(owner);
        history.setMode(plan.mode());
        history.setPeriodType(plan.periodType());
        history.setComparisonPreset(plan.comparisonPreset());
        history.setFromDate(plan.primaryRange().from());
        history.setToDate(plan.primaryRange().to());
        if (plan.comparisonRange() != null) {
            history.setCompareFromDate(plan.comparisonRange().from());
            history.setCompareToDate(plan.comparisonRange().to());
        }
        history.setModel(properties.getModel());
        history.setProvider(aiMetrics.providerLabel());
        history.setTitle(buildTitle(plan));
        return history;
    }

    private String buildTitle(AnalysisPlan plan) {
        String range = plan.primaryRange().from() + " ~ " + plan.primaryRange().to();
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && plan.comparisonRange() != null) {
            return "AI comparison analysis - " + range + " vs " + plan.comparisonRange().from() + " ~ " + plan.comparisonRange().to();
        }
        return "AI period analysis - " + range;
    }

    private LedgerAiAnalysisHistorySummaryResponse toSummary(LedgerAiAnalysisHistory history) {
        return new LedgerAiAnalysisHistorySummaryResponse(
                history.getId(),
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getStatus(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                history.getModel(),
                history.getTitle(),
                history.getSummary(),
                history.getErrorMessage(),
                history.getCreatedAt()
        );
    }

    private ExpenseEntryPayload toExpensePayload(LedgerEntryRepository.AiExpenseEntryAggregate row) {
        return new ExpenseEntryPayload(
                row.getEntryDate(),
                safeText(row.getTitle()),
                safeText(row.getMemo()),
                nullToZero(row.getAmount()),
                safeText(row.getCategoryGroupName()),
                hasText(row.getCategoryDetailName()) ? row.getCategoryDetailName() : "Uncategorized",
                safeText(row.getPaymentMethodName())
        );
    }

    private ComparisonUnit comparisonUnitFor(LedgerAiAnalysisPeriod periodType) {
        return switch (periodType) {
            case WEEK -> ComparisonUnit.WEEK;
            case YEAR -> ComparisonUnit.YEAR;
            case QUARTER -> ComparisonUnit.QUARTER;
            default -> ComparisonUnit.MONTH;
        };
    }

    private int comparisonPeriodsFor(LedgerAiAnalysisPeriod periodType) {
        return switch (periodType) {
            case WEEK -> 8;
            case YEAR -> 5;
            case QUARTER -> 8;
            default -> 6;
        };
    }

    private BigDecimal averageDailyExpense(BigDecimal expense, DateRange range) {
        long days = Math.max(1, ChronoUnit.DAYS.between(range.from(), range.to()) + 1);
        return nullToZero(expense).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    private List<RecurringExpenseCandidatePayload> buildRecurringExpenseCandidates(List<ExpenseEntryPayload> entries) {
        Map<String, RecurringExpenseAccumulator> groups = new LinkedHashMap<>();
        for (ExpenseEntryPayload entry : entries) {
            String key = recurringKey(entry);
            groups.computeIfAbsent(key, ignored -> new RecurringExpenseAccumulator(entry)).add(entry);
        }

        return groups.values().stream()
                .filter(candidate -> candidate.occurrenceCount() >= 2)
                .map(RecurringExpenseAccumulator::toPayload)
                .sorted(Comparator
                        .comparing(RecurringExpenseCandidatePayload::totalAmount, Comparator.reverseOrder())
                        .thenComparing(RecurringExpenseCandidatePayload::lastDate, Comparator.reverseOrder()))
                .limit(10)
                .toList();
    }

    private String recurringKey(ExpenseEntryPayload entry) {
        return normalizeForRecurringKey(entry.title()) + "|"
                + normalizeForRecurringKey(entry.categoryDetailName()) + "|"
                + normalizeForRecurringKey(entry.paymentMethodName());
    }

    private String normalizeForRecurringKey(String value) {
        return safeText(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private LedgerAiAnalysisReportResponse buildReport(AnalysisPlan plan, AnalysisDataset dataset, LedgerAiRemoteResponse remote) {
        LedgerAiAnalysisReportResponse fallback = buildFallbackReport(plan, dataset);
        LedgerAiAnalysisReportResponse remoteReport = remote.report();
        return new LedgerAiAnalysisReportResponse(
                firstNonBlank(remoteReport == null ? null : remoteReport.keySummary(), firstNonBlank(remote.summary(), fallback.keySummary())),
                firstNonBlank(remoteReport == null ? null : remoteReport.fullReport(), fallback.fullReport()),
                firstNonBlank(remoteReport == null ? null : remoteReport.averageAmountInsight(), fallback.averageAmountInsight()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.notableSpending(), firstNonEmpty(remote.highlights(), fallback.notableSpending())),
                firstNonEmpty(remoteReport == null ? null : remoteReport.regularSpending(), fallback.regularSpending()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.abnormalSpending(), firstNonEmpty(remote.unusualSpendingInsights(), firstNonEmpty(remote.warnings(), fallback.abnormalSpending()))),
                firstNonBlank(remoteReport == null ? null : remoteReport.topPaymentMethod(), fallback.topPaymentMethod()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.subscriptions(), fallback.subscriptions()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.fixedExpenses(), fallback.fixedExpenses()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.improvementActions(), firstNonEmpty(remote.recommendations(), fallback.improvementActions())),
                firstNonEmpty(remoteReport == null ? null : remoteReport.comparisonFocus(), firstNonEmpty(remote.trendInsights(), fallback.comparisonFocus()))
        );
    }

    private LedgerAiAnalysisReportResponse buildFallbackReport(AnalysisPlan plan, AnalysisDataset dataset) {
        DateRange primary = plan.primaryRange();
        BigDecimal totalExpense = nullToZero(dataset.overview().expense());
        BigDecimal averageExpense = averageDailyExpense(totalExpense, primary);
        long expenseCount = dataset.expenseEntries().size();
        List<PaymentSummaryPayload> expensePayments = buildExpensePaymentBreakdown(dataset.expenseEntries());
        List<RecurringExpenseCandidatePayload> recurringCandidates = buildRecurringExpenseCandidates(dataset.expenseEntries());
        List<String> comparisonFocus = buildComparisonFocus(plan, dataset);
        String topPaymentMethod = buildTopPaymentMethodInsight(expensePayments);
        String keySummary = buildKeySummary(plan, dataset, totalExpense, averageExpense, expenseCount, comparisonFocus);
        List<String> notableSpending = buildNotableSpending(dataset);
        List<String> regularSpending = buildRegularSpending(dataset);
        List<String> abnormalSpending = buildAbnormalSpending(dataset, averageExpense);
        List<String> subscriptions = buildSubscriptionInsights(recurringCandidates);
        List<String> fixedExpenses = buildFixedExpenseInsights(recurringCandidates);
        List<String> improvementActions = buildImprovementActions(plan, dataset, recurringCandidates, comparisonFocus);
        String fullReport = buildFullReport(plan, dataset, totalExpense, averageExpense, expenseCount, topPaymentMethod, notableSpending, fixedExpenses, subscriptions, abnormalSpending, improvementActions, comparisonFocus);

        return new LedgerAiAnalysisReportResponse(
                keySummary,
                fullReport,
                "????節떷??????????援온?????嶺뚮ㅎ?쒒???轅붽틓???????????? " + formatWon(averageExpense) + "????? ??" + expenseCount + "?轅몄뫅??????轅붽틓??????????욱룑????????????????????壤굿???????????????낆젵.",
                notableSpending,
                regularSpending,
                abnormalSpending,
                topPaymentMethod,
                subscriptions,
                fixedExpenses,
                improvementActions,
                comparisonFocus
        );
    }

    private String buildKeySummary(AnalysisPlan plan, AnalysisDataset dataset, BigDecimal totalExpense, BigDecimal averageExpense, long expenseCount, List<String> comparisonFocus) {
        if (expenseCount == 0) {
            return "????節떷??????????援온??????轅붽틓??????????욱룑????????욱룑????????????????怨쀫뮝力?????熬곣뫂爰???????????";
        }
        String range = plan.primaryRange().from() + " ~ " + plan.primaryRange().to();
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && dataset.comparisonOverview() != null) {
            BigDecimal compareExpense = nullToZero(dataset.comparisonOverview().expense());
            BigDecimal delta = totalExpense.subtract(compareExpense);
            return range + " ?轅붽틓???????????? " + formatWon(totalExpense) + "??????????????????援온?????" + formatSignedWon(delta)
                    + "(" + formatPercentChange(delta, compareExpense) + ")??????뽯쨦?? "
                    + (comparisonFocus.isEmpty() ? "??????????????? ????살퓢?? ???怨쀫뮝力????????밸븶???癲ル슢?????" : comparisonFocus.get(0));
        }
        return range + " ?轅붽틓???????????? ??" + formatWon(totalExpense) + ", ???嶺뚮ㅎ?쒒??" + formatWon(averageExpense)
                + "??????뽯쨦?? " + expenseCount + "?轅몄뫅??????轅붽틓?????????????????????????????????????????????읐????ル봿??誘⑸쿋?????????? ??꿔꺂??????큿???????????낆젵.";
    }

    private String buildFullReport(
            AnalysisPlan plan,
            AnalysisDataset dataset,
            BigDecimal totalExpense,
            BigDecimal averageExpense,
            long expenseCount,
            String topPaymentMethod,
            List<String> notableSpending,
            List<String> fixedExpenses,
            List<String> subscriptions,
            List<String> abnormalSpending,
            List<String> improvementActions,
            List<String> comparisonFocus
    ) {
        StringBuilder report = new StringBuilder();
        report.append("???怨쀫뮝力??????????援온?? ").append(plan.primaryRange().from()).append("????뉖???").append(plan.primaryRange().to()).append("?關?쒎첎?嫄????????뽯쨦?? ")
                .append("???轅붽틓???????????? ").append(formatWon(totalExpense)).append(", ???嶺뚮ㅎ?쒒???轅붽틓???????????? ").append(formatWon(averageExpense))
                .append("??????轅붽틓??????????욱룑??? ").append(expenseCount).append("?轅몄뫅??????????낆젵. ");
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && dataset.comparisonOverview() != null) {
            report.append("?????????????援온?").append(plan.comparisonRange().from()).append("????뉖???").append(plan.comparisonRange().to()).append("?關?쒎첎?嫄?????轅붽틓???????????? ")
                    .append(formatWon(dataset.comparisonOverview().expense())).append("?? ????????怨쀫뮝力????????? ?轅붽틓??????轅붽틓?節됰쑏???몡?곌퇇肉??影??젧???轅붽틓?節됰쑏???몡??????怨쀫뮝力?遊븀빊???곴껍癲???꿔꺂??틝???????棺堉?댆洹ⓦ럹???β뼯援?????????낆젵. ");
            appendSentences(report, comparisonFocus);
        }
        appendSentences(report, notableSpending);
        if (hasText(topPaymentMethod)) {
            report.append(topPaymentMethod).append(" ");
        }
        appendSentences(report, fixedExpenses);
        appendSentences(report, subscriptions);
        appendSentences(report, abnormalSpending);
        appendSentences(report, improvementActions);
        return report.toString().trim();
    }

    private List<String> buildNotableSpending(AnalysisDataset dataset) {
        List<String> items = new ArrayList<>();
        dataset.categoryBreakdown().stream()
                .limit(3)
                .forEach(item -> items.add(categoryLabel(item.groupName(), item.detailName()) + "??" + formatWon(item.totalAmount()) + "(" + item.entryCount() + "??????????????????낆젵."));
        dataset.topExpenses().stream()
                .limit(3)
                .forEach(item -> items.add("????숈????轅붽틓??????????밸븶??? " + item.entryDate() + " " + item.title() + " " + formatWon(item.amount()) + "."));
        return items;
    }

    private List<String> buildRegularSpending(AnalysisDataset dataset) {
        return dataset.categoryBreakdown().stream()
                .sorted(Comparator.comparing(CategoryBreakdownItemResponse::entryCount).reversed())
                .limit(3)
                .map(item -> categoryLabel(item.groupName(), item.detailName()) + "?? " + item.entryCount() + "?轅몄뫅??????????ш끽維뽳쭩???????ш낄??????? ????붺몭?⑸쨨營먲쭪??????낆젵.")
                .toList();
    }

    private List<String> buildAbnormalSpending(AnalysisDataset dataset, BigDecimal averageExpense) {
        List<String> items = new ArrayList<>();
        dataset.topExpenses().stream()
                .filter(item -> item.amount().compareTo(averageExpense) > 0)
                .limit(3)
                .forEach(item -> items.add(item.entryDate() + "??" + item.title() + "(" + formatWon(item.amount()) + ")?? ???嶺뚮ㅎ?쒒???轅붽틓????????????????????숈????轅붽틓??????????????????낆젵."));
        if (items.isEmpty() && !dataset.topExpenses().isEmpty()) {
            ExpenseEntryPayload top = dataset.topExpenses().get(0);
            items.add("???ル봿??????????裕뼘??轅붽틓???????????? " + top.entryDate() + " " + top.title() + " " + formatWon(top.amount()) + "??????뽯쨦??");
        }
        return items;
    }

    private List<String> buildSubscriptionInsights(List<RecurringExpenseCandidatePayload> recurringCandidates) {
        List<String> subscriptions = recurringCandidates.stream()
                .filter(item -> item.title().toLowerCase(Locale.ROOT).contains("subscription") || item.title().toLowerCase(Locale.ROOT).contains("subscribe"))
                .limit(5)
                .map(item -> item.title() + "?? " + item.occurrenceCount() + "????ш끽維뽳쭩????癲ル슢??????????節뉗땡???轅붽틓??????????밸븶????????뽯쨦??")
                .toList();
        return subscriptions.isEmpty() ? List.of("?轅붽틓??筌뚮챶夷????????節뉗땡????ш끽維뽳쭩????轅붽틓???????????? ??꿔꺂??틝??????? ?????源낅돹??????") : subscriptions;
    }

    private List<String> buildFixedExpenseInsights(List<RecurringExpenseCandidatePayload> recurringCandidates) {
        if (recurringCandidates.isEmpty()) {
            return List.of("?????⑤베????癲ル슢흮?룰쑤援? ???怨쀫뮝力?遊븀빊? ??β뼯援??????棺堉?댆?????????2???????鶯???ш끽維뽳쭩????????숈?????????????밸븶??????꿔꺂??틝??????? ?????源낅돹??????");
        }
        return recurringCandidates.stream()
                .limit(5)
                .map(item -> item.title() + "?? " + item.firstDate() + "????뉖???" + item.lastDate() + "?關?쒎첎?嫄?? " + item.occurrenceCount()
                        + "????ш끽維뽳쭩????癲???????룸챸猷???" + formatWon(item.totalAmount()) + "??????뽯쨦??")
                .toList();
    }

    private List<String> buildImprovementActions(AnalysisPlan plan, AnalysisDataset dataset, List<RecurringExpenseCandidatePayload> recurringCandidates, List<String> comparisonFocus) {
        List<String> actions = new ArrayList<>();
        dataset.categoryBreakdown().stream().findFirst()
                .ifPresent(item -> actions.add(categoryLabel(item.groupName(), item.detailName()) + " ?轅붽틓?????????????????????? ????????????ル봿??誘⑸쿋??????鶯ㅺ동????ロ닞???ル봿?? ???ル봿??????????낅쨦??"));
        if (!recurringCandidates.isEmpty()) {
            actions.add("??ш끽維뽳쭩????轅붽틓??????????밸븶???????濚밸Ŧ?김??????숈??????욍걖????밸븶??뫢?蹂ο폎????깆궔? ??꿔꺂??틝????????롪퍓肉?? ???怨쀫뎐?????????????? ??? ???????β뼯援????????용츧???⑤８?????곗뒭???????β뼯援??????節떷???꿔꺂?????");
        }
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && !comparisonFocus.isEmpty()) {
            actions.add("?????????????援온?????????轅붽틓?節됰쑏???몡??????怨쀫뮝力?遊븀빊???곴껍癲????濚밸Ŧ援??????????援온????????????釉뚯뺏??????댟?蹂?떻??戮?츐?????댁삩????숆강???κ뭬???꿔꺂?????");
        }
        if (actions.isEmpty()) {
            actions.add("?轅몄뫅??????????????? ??濡ろ뜐筌?쓣????? ????繹먮굝堉 ???ル봿??誘⑸쿋??????癲???? ??????????????⑤챷竊????????ㅻ쿋????꿔꺂??틝???????棺堉?댆洹ⓦ럹???β뼯援?????????????????낆젵.");
        }
        return actions;
    }

    private List<String> buildComparisonFocus(AnalysisPlan plan, AnalysisDataset dataset) {
        if (plan.mode() != LedgerAiAnalysisMode.COMPARISON || dataset.comparisonOverview() == null) {
            return List.of();
        }
        BigDecimal currentExpense = nullToZero(dataset.overview().expense());
        BigDecimal compareExpense = nullToZero(dataset.comparisonOverview().expense());
        BigDecimal delta = currentExpense.subtract(compareExpense);
        List<String> insights = new ArrayList<>();
        if (dataset.expenseEntries().isEmpty() && !dataset.comparisonExpenseEntries().isEmpty()) {
            insights.add("???怨쀫뮝力????????? ????????援온??????轅붽틓??????????욱룑???????泳?뿀???????????????援온??????轅붽틓??????????????????Β?띾쭡, ???濚밸Ŧ?김??????????ル봿?????? ??????????????밸븶?④섣???? ??꿔꺂??틝???????????밸븶???癲ル슢?????");
        } else {
            insights.add("??????? ????????援온??轅붽틓???????????? ?????????????援온?????" + formatSignedWon(delta) + "(" + formatPercentChange(delta, compareExpense) + ")??????뽯쨦??");
        }
        Map<String, BigDecimal> currentCategories = categoryAmountMap(dataset.categoryBreakdown());
        Map<String, BigDecimal> compareCategories = categoryAmountMap(dataset.comparisonCategoryBreakdown());
        List<String> categoryLabels = new ArrayList<>();
        categoryLabels.addAll(currentCategories.keySet());
        for (String label : compareCategories.keySet()) {
            if (!categoryLabels.contains(label)) {
                categoryLabels.add(label);
            }
        }
        categoryLabels.stream()
                .map(label -> new CategoryDelta(label, currentCategories.getOrDefault(label, BigDecimal.ZERO), compareCategories.getOrDefault(label, BigDecimal.ZERO)))
                .sorted(Comparator.comparing(CategoryDelta::absDelta).reversed())
                .limit(3)
                .forEach(item -> insights.add(item.label() + " ???ㅼ뒧???? " + formatSignedWon(item.delta()) + "."));
        return insights;
    }

    private List<PaymentSummaryPayload> buildExpensePaymentBreakdown(List<ExpenseEntryPayload> entries) {
        Map<String, PaymentAccumulator> groups = new LinkedHashMap<>();
        for (ExpenseEntryPayload entry : entries) {
            String paymentName = hasText(entry.paymentMethodName()) ? entry.paymentMethodName() : "Uncategorized";
            groups.computeIfAbsent(paymentName, PaymentAccumulator::new).add(entry.amount());
        }
        return groups.values().stream()
                .map(PaymentAccumulator::toPayload)
                .sorted(Comparator
                        .comparing(PaymentSummaryPayload::totalAmount, Comparator.reverseOrder())
                        .thenComparing(PaymentSummaryPayload::entryCount, Comparator.reverseOrder()))
                .toList();
    }

    private String buildTopPaymentMethodInsight(List<PaymentSummaryPayload> expensePayments) {
        if (expensePayments.isEmpty()) {
            return "?轅붽틓???????????? ??β뼯援??????棺堉?댆???????????? ?????욱룏???????낆젵.";
        }
        PaymentSummaryPayload top = expensePayments.get(0);
        return "?轅붽틓???????????? ???ル봿?????轅붽틓????????????β뼯援??????棺堉?댆??? " + top.paymentMethodName() + "????? ????룸챸猷??" + formatWon(top.totalAmount()) + "(" + top.entryCount() + "????????뽯쨦??";
    }

    private Map<String, BigDecimal> categoryAmountMap(List<CategoryBreakdownItemResponse> items) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (CategoryBreakdownItemResponse item : items) {
            result.put(categoryLabel(item.groupName(), item.detailName()), nullToZero(item.totalAmount()));
        }
        return result;
    }

    private String categoryLabel(String groupName, String detailName) {
        if (hasText(detailName)) {
            return safeText(groupName) + " / " + detailName;
        }
        return safeText(groupName);
    }

    private void appendSentences(StringBuilder report, List<String> sentences) {
        for (String sentence : sentences) {
            if (hasText(sentence)) {
                report.append(sentence).append(" ");
            }
        }
    }

    private List<String> concatLists(List<String> first, List<String> second, List<String> third) {
        List<String> result = new ArrayList<>();
        result.addAll(firstNonEmpty(first, List.of()));
        result.addAll(firstNonEmpty(second, List.of()));
        result.addAll(firstNonEmpty(third, List.of()));
        return result;
    }

    private List<String> singleItemList(String value) {
        return hasText(value) ? List.of(value) : List.of();
    }

    private String firstNonBlank(String primary, String fallback) {
        return hasText(primary) ? primary : safeText(fallback);
    }

    private String formatWon(BigDecimal value) {
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.KOREA);
        return "KRW " + formatter.format(nullToZero(value).setScale(0, RoundingMode.HALF_UP));
    }

    private String formatSignedWon(BigDecimal value) {
        BigDecimal safeValue = nullToZero(value);
        if (safeValue.signum() > 0) {
            return "+" + formatWon(safeValue);
        }
        if (safeValue.signum() < 0) {
            return "-" + formatWon(safeValue.abs());
        }
        return formatWon(BigDecimal.ZERO);
    }

    private String formatPercentChange(BigDecimal delta, BigDecimal base) {
        BigDecimal safeBase = nullToZero(base);
        if (safeBase.compareTo(BigDecimal.ZERO) == 0) {
            return nullToZero(delta).compareTo(BigDecimal.ZERO) == 0 ? "0.00%" : "?????????????援온?0???癲ル슢?뤺キ?????????壤굿?????????????;
        }
        return nullToZero(delta)
                .multiply(BigDecimal.valueOf(100))
                .divide(safeBase, 2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI \uBD84\uC11D \uB370\uC774\uD130\uB97C JSON\uC73C\uB85C \uC800\uC7A5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
        }
    }

    private LedgerAiAnalysisResponse readResult(String resultJson) {
        if (!hasText(resultJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(resultJson, LedgerAiAnalysisResponse.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String limitText(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record ComparisonRanges(LedgerAiAnalysisPeriod periodType, DateRange primary, DateRange comparison) {
    }

    private record AnalysisPlan(
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset,
            DateRange primaryRange,
            DateRange comparisonRange
    ) {
    }

    private record CategoryDelta(String label, BigDecimal currentAmount, BigDecimal compareAmount) {
        private BigDecimal delta() {
            return currentAmount.subtract(compareAmount);
        }

        private BigDecimal absDelta() {
            return delta().abs();
        }
    }

    private record AnalysisDataset(
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

    public record LedgerAiN8nPayload(
            String schemaVersion,
            String language,
            String model,
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset,
            LocalDate from,
            LocalDate to,
            LocalDate compareFrom,
            LocalDate compareTo,
            OverviewResponse overview,
            List<CategoryBreakdownItemResponse> categoryBreakdown,
            List<PaymentBreakdownItemResponse> paymentBreakdown,
            List<PaymentSummaryPayload> expensePaymentBreakdown,
            List<PeriodComparisonItemResponse> periodComparison,
            List<ExpenseEntryPayload> expenseEntries,
            List<ExpenseEntryPayload> topExpenses,
            List<RecurringExpenseCandidatePayload> recurringExpenseCandidates,
            OverviewResponse comparisonOverview,
            List<CategoryBreakdownItemResponse> comparisonCategoryBreakdown,
            List<PaymentBreakdownItemResponse> comparisonPaymentBreakdown,
            List<ExpenseEntryPayload> comparisonExpenseEntries,
            PayloadMinimizationSummary payloadMinimization,
            String outputContract
    ) {
    }

    public record PayloadMinimizationSummary(
            int expenseEntryTotalCount,
            int expenseEntrySentCount,
            int expenseEntryOverflowCount,
            int comparisonExpenseEntryTotalCount,
            int comparisonExpenseEntrySentCount,
            int comparisonExpenseEntryOverflowCount,
            int textLimit,
            int memoLimit
    ) {
    }

    public record ExpenseEntryPayload(
            LocalDate entryDate,
            String title,
            String memo,
            BigDecimal amount,
            String categoryGroupName,
            String categoryDetailName,
            String paymentMethodName
    ) {
    }

    public record PaymentSummaryPayload(
            String paymentMethodName,
            BigDecimal totalAmount,
            long entryCount
    ) {
    }

    public record RecurringExpenseCandidatePayload(
            String title,
            String categoryGroupName,
            String categoryDetailName,
            String paymentMethodName,
            int occurrenceCount,
            BigDecimal totalAmount,
            BigDecimal averageAmount,
            LocalDate firstDate,
            LocalDate lastDate,
            List<LocalDate> dates
    ) {
    }

    private static final class PaymentAccumulator {

        private final String paymentMethodName;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private long entryCount;

        private PaymentAccumulator(String paymentMethodName) {
            this.paymentMethodName = paymentMethodName;
        }

        private void add(BigDecimal amount) {
            totalAmount = totalAmount.add(nullToStaticZero(amount));
            entryCount += 1;
        }

        private PaymentSummaryPayload toPayload() {
            return new PaymentSummaryPayload(paymentMethodName, totalAmount, entryCount);
        }
    }

    private static BigDecimal nullToStaticZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class RecurringExpenseAccumulator {

        private final String title;
        private final String categoryGroupName;
        private final String categoryDetailName;
        private final String paymentMethodName;
        private final List<LocalDate> dates = new ArrayList<>();
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private LocalDate firstDate;
        private LocalDate lastDate;

        private RecurringExpenseAccumulator(ExpenseEntryPayload firstEntry) {
            this.title = firstEntry.title();
            this.categoryGroupName = firstEntry.categoryGroupName();
            this.categoryDetailName = firstEntry.categoryDetailName();
            this.paymentMethodName = firstEntry.paymentMethodName();
        }

        private void add(ExpenseEntryPayload entry) {
            totalAmount = totalAmount.add(entry.amount());
            dates.add(entry.entryDate());
            if (firstDate == null || entry.entryDate().isBefore(firstDate)) {
                firstDate = entry.entryDate();
            }
            if (lastDate == null || entry.entryDate().isAfter(lastDate)) {
                lastDate = entry.entryDate();
            }
        }

        private int occurrenceCount() {
            return dates.size();
        }

        private RecurringExpenseCandidatePayload toPayload() {
            BigDecimal averageAmount = totalAmount.divide(BigDecimal.valueOf(occurrenceCount()), 2, RoundingMode.HALF_UP);
            return new RecurringExpenseCandidatePayload(
                    title,
                    categoryGroupName,
                    categoryDetailName,
                    paymentMethodName,
                    occurrenceCount(),
                    totalAmount,
                    averageAmount,
                    firstDate,
                    lastDate,
                    List.copyOf(dates)
            );
        }
    }
}


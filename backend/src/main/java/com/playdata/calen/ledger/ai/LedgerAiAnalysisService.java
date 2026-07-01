package com.playdata.calen.ledger.ai;

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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
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
    private static final int MAX_HISTORY_PAGE_SIZE = 50;
    private static final long MAX_CUSTOM_RANGE_DAYS = 366;
    private static final Duration DUPLICATE_SUPPRESSION_WINDOW = Duration.ofMinutes(5);

    private final AppUserService appUserService;
    private final StatisticsService statisticsService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiAnalysisStatusService statusService;
    private final LedgerAiRemoteClient remoteClient;
    private final LedgerAiAnalysisMetrics aiMetrics;
    private final LedgerAiAnalysisJsonCodec aiJsonCodec;
    private final LedgerAiAnalysisTextSanitizer aiText;
    private final LedgerAiAnalysisPayloadBuilder aiPayloadBuilder;
    private final LedgerAiAnalysisReportMerger aiReportMerger;
    private final LedgerAiAnalysisNotifications aiNotifications;

    private final Map<String, Object> inFlightAnalysisLocks = new ConcurrentHashMap<>();

    public LedgerAiAnalysisStatusResponse getStatus() {
        return statusService.getStatus();
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public LedgerAiAnalysisResponse analyze(Long userId, LedgerAiAnalysisRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        if (!properties.isConfigured()) {
            throw new BadRequestException(properties.statusMessage());
        }

        AnalysisPlan plan = resolvePlan(request);
        String clientRequestId = normalizeClientRequestId(request.clientRequestId());
        String inFlightKey = analysisInFlightKey(userId, plan, clientRequestId);
        Object lock = inFlightAnalysisLocks.computeIfAbsent(inFlightKey, ignored -> new Object());
        try {
            synchronized (lock) {
                return analyzeResolvedPlan(owner, userId, plan);
            }
        } finally {
            inFlightAnalysisLocks.remove(inFlightKey, lock);
        }
    }

    private LedgerAiAnalysisResponse analyzeResolvedPlan(AppUser owner, Long userId, AnalysisPlan plan) {
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
            history.setSummary(aiText.safeText(remote.summary()));
            history.setRequestPayloadJson(aiJsonCodec.write(payload));
            history = historyRepository.save(history);
            aiNotifications.notifyCompleted(userId, history);

            LedgerAiAnalysisResponse response = buildResponse(history.getId(), plan, dataset, remote);
            history.setResultJson(aiJsonCodec.write(response));
            return response;
        } catch (RuntimeException exception) {
            if (!aiRequestRecorded) {
                aiMetrics.recordAiRequest(aiRequestTimer, "failure");
            }
            LedgerAiAnalysisHistory failedHistory = baseHistory(owner, plan);
            failedHistory.setStatus(LedgerAiAnalysisStatus.FAILED);
            failedHistory.setSummary("AI analysis failed.");
            failedHistory.setErrorMessage(aiText.limitText(exception.getMessage(), 500));
            failedHistory.setRequestPayloadJson(aiJsonCodec.write(payload));
            failedHistory = historyRepository.save(failedHistory);
            aiNotifications.notifyFailed(userId, failedHistory);
            throw exception;
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
            throw new BadRequestException("조회 시작일은 종료일보다 이후일 수 없습니다.");
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
                .orElseThrow(() -> new NotFoundException("AI 분석 이력을 찾을 수 없습니다."));
        LedgerAiAnalysisResponse result = readStoredHistoryResultOrFallback(history);
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
                .orElseThrow(() -> new NotFoundException("AI 분석 이력을 찾을 수 없습니다."));
        return analyze(userId, new LedgerAiAnalysisRequest(
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getToDate(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                null
        ));
    }

    public LedgerAiAnalysisHistoryDetailResponse getLatestMatching(Long userId, LedgerAiAnalysisRequest request) {
        appUserService.getRequiredUser(userId);
        AnalysisPlan plan = resolvePlan(request);
        return findLatestMatchingAnalysis(userId, plan, null)
                .map(history -> new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), readStoredHistoryResultOrFallback(history)))
                .orElse(null);
    }

    private String normalizeClientRequestId(String clientRequestId) {
        return clientRequestId == null ? "" : clientRequestId.trim();
    }

    private String analysisInFlightKey(Long userId, AnalysisPlan plan, String clientRequestId) {
        DateRange comparison = plan.comparisonRange();
        return String.join("|",
                String.valueOf(userId),
                aiMetrics.providerLabel(),
                properties.getModel(),
                String.valueOf(plan.mode()),
                String.valueOf(plan.periodType()),
                String.valueOf(plan.primaryRange().from()),
                String.valueOf(plan.primaryRange().to()),
                comparison == null ? "" : String.valueOf(comparison.from()),
                comparison == null ? "" : String.valueOf(comparison.to()),
                clientRequestId == null ? "" : clientRequestId
        );
    }

    private LedgerAiAnalysisResponse readStoredHistoryResultOrFallback(LedgerAiAnalysisHistory history) {
        if (history == null) {
            return null;
        }
        if (history.getStatus() != LedgerAiAnalysisStatus.COMPLETED || !aiText.hasText(history.getResultJson())) {
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 없습니다. 재분석을 실행해 주세요.");
        }
        try {
            LedgerAiAnalysisResponse result = aiJsonCodec.readResult(history.getResultJson());
            return result == null
                    ? buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 비어 있습니다. 재분석을 실행해 주세요.")
                    : result;
        } catch (RuntimeException exception) {
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과를 읽지 못했습니다. 재분석을 실행해 주세요.");
        }
    }

    private LedgerAiAnalysisResponse readReusableStoredHistoryResult(LedgerAiAnalysisHistory history) {
        if (history == null || history.getStatus() != LedgerAiAnalysisStatus.COMPLETED || !aiText.hasText(history.getResultJson())) {
            return null;
        }
        try {
            return aiJsonCodec.readResult(history.getResultJson());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LedgerAiAnalysisResponse buildUnreadableHistoryResult(LedgerAiAnalysisHistory history, String message) {
        String summary = firstNonBlank(history.getSummary(), message);
        LedgerAiAnalysisReportResponse report = new LedgerAiAnalysisReportResponse(
                summary,
                "이 저장 이력의 원본 분석 결과가 비어 있거나 현재 응답 형식과 맞지 않습니다. 재분석 버튼으로 새 결과를 생성해 주세요.",
                "저장된 상세 지표를 읽을 수 없어 금액 분석은 표시하지 않습니다.",
                List.of(),
                List.of(),
                List.of(message),
                "",
                List.of(),
                List.of(),
                List.of("이 이력을 재분석해 새 결과를 저장하세요."),
                List.of()
        );
        return new LedgerAiAnalysisResponse(
                history.getId(),
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                history.getModel(),
                history.getCreatedAt() == null ? Instant.now() : history.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                List.of(),
                report,
                summary,
                List.of("저장된 분석 이력은 확인됐지만 상세 결과를 읽을 수 없습니다."),
                List.of(message),
                List.of("재분석을 실행해 현재 형식의 결과를 다시 저장하세요."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ""
        );
    }
    private LedgerAiAnalysisResponse findReusableAnalysis(Long userId, AnalysisPlan plan) {
        LocalDateTime createdAfter = LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW);
        return findLatestMatchingAnalysis(userId, plan, createdAfter)
                .map(this::readReusableStoredHistoryResult)
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
        List<ExpenseEntryPayload> providerExpenseEntries = aiPayloadBuilder.providerExpenseEntries(dataset.expenseEntries(), PROVIDER_EXPENSE_ENTRY_LIMIT);
        List<ExpenseEntryPayload> providerTopExpenses = aiPayloadBuilder.providerExpenseEntries(dataset.topExpenses(), TOP_EXPENSE_LIMIT);
        List<ExpenseEntryPayload> providerComparisonExpenseEntries = aiPayloadBuilder.providerExpenseEntries(dataset.comparisonExpenseEntries(), PROVIDER_COMPARISON_ENTRY_LIMIT);
        List<RecurringExpenseCandidatePayload> providerRecurringCandidates = aiPayloadBuilder.providerRecurringCandidates(buildRecurringExpenseCandidates(dataset.expenseEntries()));

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
                aiPayloadBuilder.buildPayloadMinimizationSummary(dataset.expenseEntries().size(), dataset.comparisonExpenseEntries().size(), providerExpenseEntries, providerComparisonExpenseEntries),
                LedgerAiOutputContract.text()
        );
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
            throw new BadRequestException(label + " must be within 1 year.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(label + " must be within 1 year.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_CUSTOM_RANGE_DAYS) {
            throw new BadRequestException(label + " must be within 1 year.");
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
                aiText.safeText(row.getTitle()),
                aiText.safeText(row.getMemo()),
                nullToZero(row.getAmount()),
                aiText.safeText(row.getCategoryGroupName()),
                aiText.hasText(row.getCategoryDetailName()) ? row.getCategoryDetailName() : "Uncategorized",
                aiText.safeText(row.getPaymentMethodName())
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
        return aiText.safeText(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private LedgerAiAnalysisReportResponse buildReport(AnalysisPlan plan, AnalysisDataset dataset, LedgerAiRemoteResponse remote) {
        return aiReportMerger.merge(buildFallbackReport(plan, dataset), remote);
    }

    private LedgerAiAnalysisReportResponse buildFallbackReport(AnalysisPlan plan, AnalysisDataset dataset) {
        DateRange primary = plan.primaryRange();
        BigDecimal totalExpense = nullToZero(dataset.overview().expense());
        BigDecimal averageExpense = averageDailyExpense(totalExpense, primary);
        long expenseCount = dataset.expenseEntries().size();
        List<PaymentSummaryPayload> expensePayments = buildExpensePaymentBreakdown(dataset.expenseEntries());
        List<RecurringExpenseCandidatePayload> recurringCandidates = buildRecurringExpenseCandidates(dataset.expenseEntries());
        List<String> comparisonFocus = buildComparisonFocus(plan, dataset);
        String topPaymentMethod = buildTopPaymentMethodInsight(expensePayments, totalExpense);
        String keySummary = buildKeySummary(plan, dataset, totalExpense, averageExpense, expenseCount, comparisonFocus);
        List<String> notableSpending = buildNotableSpending(dataset);
        List<String> regularSpending = buildRegularSpending(dataset, recurringCandidates);
        List<String> abnormalSpending = buildAbnormalSpending(dataset, averageExpense);
        List<String> subscriptions = buildSubscriptionInsights(recurringCandidates);
        List<String> fixedExpenses = buildFixedExpenseInsights(recurringCandidates);
        List<String> improvementActions = buildImprovementActions(plan, dataset, recurringCandidates, comparisonFocus);
        String fullReport = buildFullReport(plan, dataset, totalExpense, averageExpense, expenseCount, topPaymentMethod, notableSpending, fixedExpenses, subscriptions, regularSpending, abnormalSpending, improvementActions, comparisonFocus);

        return new LedgerAiAnalysisReportResponse(
                keySummary,
                fullReport,
                "선택 기간의 하루 평균 지출은 " + formatWon(averageExpense) + "이고, 총 " + expenseCount + "건의 지출이 확인됩니다.",
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
            return "선택한 기간에는 지출 데이터가 없어 분석 근거가 충분하지 않습니다.";
        }
        String range = plan.primaryRange().from() + " ~ " + plan.primaryRange().to();
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && dataset.comparisonOverview() != null) {
            BigDecimal compareExpense = nullToZero(dataset.comparisonOverview().expense());
            BigDecimal delta = totalExpense.subtract(compareExpense);
            return range + " 총 지출은 " + formatWon(totalExpense) + "이며, 비교 기간 대비 " + formatSignedWon(delta)
                    + "(" + formatPercentChange(delta, compareExpense) + ")입니다. "
                    + (comparisonFocus.isEmpty() ? "차이가 큰 항목부터 원인을 확인하세요." : comparisonFocus.get(0));
        }
        return range + " 총 지출은 " + formatWon(totalExpense) + ", 하루 평균 지출은 " + formatWon(averageExpense)
                + "입니다. 총 " + expenseCount + "건의 지출을 기준으로 소비 패턴을 정리했습니다.";
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
            List<String> regularSpending,
            List<String> abnormalSpending,
            List<String> improvementActions,
            List<String> comparisonFocus
    ) {
        StringBuilder report = new StringBuilder();
        report.append("분석 기간은 ").append(plan.primaryRange().from()).append("부터 ").append(plan.primaryRange().to()).append("까지입니다. ")
                .append("총 지출은 ").append(formatWon(totalExpense)).append(", 하루 평균 지출은 ").append(formatWon(averageExpense))
                .append("이며 지출 거래는 ").append(expenseCount).append("건입니다. ");
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && dataset.comparisonOverview() != null) {
            report.append("비교 기간은 ").append(plan.comparisonRange().from()).append("부터 ").append(plan.comparisonRange().to()).append("까지이며, 비교 기간 총 지출은 ")
                    .append(formatWon(dataset.comparisonOverview().expense())).append("입니다. ");
            appendSentences(report, comparisonFocus);
        } else {
            report.append("비교 기간이 없으므로 증가 또는 감소 판단은 제공하지 않습니다. ");
        }
        appendSentences(report, notableSpending);
        if (aiText.hasText(topPaymentMethod)) {
            report.append(topPaymentMethod).append(" ");
        }
        appendSentences(report, fixedExpenses);
        appendSentences(report, subscriptions);
        appendSentences(report, regularSpending);
        appendSentences(report, abnormalSpending);
        appendSentences(report, improvementActions);
        return report.toString().trim();
    }

    private List<String> buildNotableSpending(AnalysisDataset dataset) {
        List<String> items = new ArrayList<>();
        dataset.categoryBreakdown().stream()
                .limit(3)
                .forEach(item -> items.add(categoryLabel(item.groupName(), item.detailName()) + "에 " + formatWon(item.totalAmount()) + "(" + item.entryCount() + "건)을 사용했습니다."));
        dataset.topExpenses().stream()
                .limit(5)
                .forEach(item -> items.add("일회성 고액 지출 후보: " + item.entryDate() + " " + item.title() + " " + formatWon(item.amount()) + "."));
        return items;
    }

    private List<String> buildRegularSpending(AnalysisDataset dataset, List<RecurringExpenseCandidatePayload> recurringCandidates) {
        List<String> variableRepeats = recurringCandidates.stream()
                .filter(this::isRecurringVariableCandidate)
                .limit(5)
                .map(item -> "반복성 변동비: " + item.title() + "은 " + item.firstDate() + "부터 " + item.lastDate() + "까지 " + item.occurrenceCount()
                        + "회 반복되었고 누적 금액은 " + formatWon(item.totalAmount()) + "입니다. 고정비로 단정하지 말고 월 상한을 정해 관리하세요.")
                .toList();
        if (!variableRepeats.isEmpty()) {
            return variableRepeats;
        }
        return dataset.categoryBreakdown().stream()
                .sorted(Comparator.comparing(CategoryBreakdownItemResponse::entryCount).reversed())
                .limit(3)
                .map(item -> categoryLabel(item.groupName(), item.detailName()) + "은 " + item.entryCount() + "건 반복되어 자주 발생하는 지출입니다. 고정비 여부는 결제 목적을 확인한 뒤 분류하세요.")
                .toList();
    }

    private List<String> buildAbnormalSpending(AnalysisDataset dataset, BigDecimal averageExpense) {
        List<String> items = new ArrayList<>();
        BigDecimal highExpenseThreshold = averageExpense.max(BigDecimal.valueOf(100_000));
        dataset.topExpenses().stream()
                .filter(item -> item.amount().compareTo(highExpenseThreshold) > 0)
                .limit(5)
                .forEach(item -> items.add(item.entryDate() + " " + item.title() + "(" + formatWon(item.amount()) + ")은 평균 지출보다 큰 일회성 고액 지출 후보입니다."));
        if (items.isEmpty() && !dataset.topExpenses().isEmpty()) {
            ExpenseEntryPayload top = dataset.topExpenses().get(0);
            items.add("가장 큰 지출은 " + top.entryDate() + " " + top.title() + " " + formatWon(top.amount()) + "입니다.");
        }
        return items;
    }

    private List<String> buildSubscriptionInsights(List<RecurringExpenseCandidatePayload> recurringCandidates) {
        List<String> subscriptions = recurringCandidates.stream()
                .filter(this::isSubscriptionCandidate)
                .limit(5)
                .map(item -> "구독비: " + item.title() + "은 " + item.occurrenceCount() + "회 반복되었고 누적 금액은 " + formatWon(item.totalAmount()) + "입니다. 최근 사용 빈도 기준으로 유지/해지 후보를 나누세요.")
                .toList();
        return subscriptions.isEmpty() ? List.of("명확한 구독비 후보는 아직 확인되지 않았습니다. 라프텔, 톡서랍, 멤버십, 클라우드 같은 정기 결제 항목을 별도로 점검하세요.") : subscriptions;
    }

    private List<String> buildFixedExpenseInsights(List<RecurringExpenseCandidatePayload> recurringCandidates) {
        List<String> fixedExpenses = recurringCandidates.stream()
                .filter(this::isFixedExpenseCandidate)
                .limit(5)
                .map(item -> "고정비: " + item.title() + "은 " + item.firstDate() + "부터 " + item.lastDate() + "까지 " + item.occurrenceCount()
                        + "회 반복되었고 누적 금액은 " + formatWon(item.totalAmount()) + "입니다. 통신비, 보험, 관리비처럼 매월 필요한 지출인지 확인하세요.")
                .toList();
        return fixedExpenses.isEmpty() ? List.of("통신비, 보험, 관리비처럼 고정비로 분류할 만한 반복 지출 후보가 충분하지 않습니다.") : fixedExpenses;
    }

    private List<String> buildImprovementActions(AnalysisPlan plan, AnalysisDataset dataset, List<RecurringExpenseCandidatePayload> recurringCandidates, List<String> comparisonFocus) {
        List<String> actions = new ArrayList<>();
        dataset.categoryBreakdown().stream().findFirst().ifPresent(item -> {
            BigDecimal suggestedLimit = nullToZero(item.totalAmount()).multiply(BigDecimal.valueOf(0.85)).setScale(0, RoundingMode.HALF_UP);
            actions.add(categoryLabel(item.groupName(), item.detailName()) + " 지출은 " + formatWon(item.totalAmount()) + "입니다. 다음 기간 1차 관리 한도를 " + formatWon(suggestedLimit) + "로 두고, 초과하면 필수/선택 지출을 분리해 재검토하세요.");
        });
        dataset.topExpenses().stream().findFirst().ifPresent(item -> actions.add("일회성 고액 지출은 별도 '비정기 지출' 항목으로 분리하세요. 예: " + item.title() + " " + formatWon(item.amount()) + "은 월 생활비와 섞지 말고 월 한도 또는 분기 한도로 관리하세요."));
        recurringCandidates.stream().filter(this::isSubscriptionCandidate).findFirst().ifPresent(item -> actions.add("구독 서비스는 사용 빈도를 기준으로 유지/해지 후보를 나누세요. " + item.title() + "은 누적 " + formatWon(item.totalAmount()) + "이므로 월 1회 이상 사용하지 않으면 해지 후보로 표시하세요."));
        recurringCandidates.stream().filter(this::isRecurringVariableCandidate).findFirst().ifPresent(item -> actions.add("게임, 충전, 간편결제 같은 반복성 변동비는 월 상한을 정하세요. " + item.title() + "은 평균 결제액 " + formatWon(item.averageAmount()) + " 기준으로 허용 횟수를 정하고 초과 결제는 다음 달로 미루는 규칙을 두세요."));
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && !comparisonFocus.isEmpty()) {
            actions.add("비교 기간 대비 증가한 항목은 원인을 확인한 뒤 다음 기간 예산에 반영하세요. 증가 원인이 일회성이라면 반복 예산에 포함하지 마세요.");
        }
        if (actions.isEmpty()) {
            actions.add("지출 데이터가 충분하지 않습니다. 거래를 더 입력한 뒤 다시 분석하면 더 구체적인 예산 기준을 제안할 수 있습니다.");
        }
        return actions;
    }

    private List<String> buildFutureImprovementDirections(AnalysisDataset dataset, List<RecurringExpenseCandidatePayload> recurringCandidates) {
        List<String> directions = new ArrayList<>();
        BigDecimal totalExpense = nullToZero(dataset.overview().expense());
        dataset.categoryBreakdown().stream().findFirst().ifPresent(item -> {
            BigDecimal target = nullToZero(item.totalAmount()).multiply(BigDecimal.valueOf(0.9)).setScale(0, RoundingMode.HALF_UP);
            directions.add("개선 방향: " + categoryLabel(item.groupName(), item.detailName()) + "처럼 비중이 큰 지출 축은 다음 기간 목표 금액을 " + formatWon(target) + " 안팎으로 낮춰 관리하세요.");
        });
        dataset.topExpenses().stream().findFirst().ifPresent(item -> directions.add("실행 방법: " + item.title() + " 같은 고액 지출은 생활비와 분리해 '비정기 지출'로 표시하고, 월 한도보다 분기 한도로 관리하세요."));
        recurringCandidates.stream().filter(this::isSubscriptionCandidate).findFirst().ifPresent(item -> directions.add("관리 수단: " + item.title() + " 같은 구독성 지출은 사용 빈도, 월 금액, 대체 가능성을 기준으로 유지/보류/해지 후보로 나누세요."));
        recurringCandidates.stream().filter(this::isRecurringVariableCandidate).findFirst().ifPresent(item -> directions.add("관리 수단: " + item.title() + " 같은 반복성 변동비는 월 상한액과 결제 횟수 제한을 함께 설정하고, 초과 시 다음 달로 미루는 규칙을 두세요."));
        if (totalExpense.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal alertLine = totalExpense.multiply(BigDecimal.valueOf(0.8)).setScale(0, RoundingMode.HALF_UP);
            directions.add("점검 수단: 다음 분석 기간에는 총지출이 " + formatWon(alertLine) + "에 도달하면 중간 점검 알림을 띄우고, 월말 PDF 보고서에서 예산 초과 원인을 확인하세요.");
        }
        return directions;
    }
    private boolean isSubscriptionCandidate(RecurringExpenseCandidatePayload item) {
        String text = recurringText(item);
        return containsAny(text, List.of("구독", "subscription", "subscribe", "멤버십", "프리미엄", "라프텔", "톡서랍", "넷플릭스", "유튜브", "웨이브", "티빙", "왓챠", "디즈니", "클라우드", "icloud", "apple", "google one", "쿠팡와우"));
    }

    private boolean isFixedExpenseCandidate(RecurringExpenseCandidatePayload item) {
        String text = recurringText(item);
        return containsAny(text, List.of("통신", "휴대폰", "인터넷", "보험", "관리비", "월세", "렌트", "대출", "이자", "전기", "가스", "수도", "요금", "정기권", "렌탈"));
    }

    private boolean isRecurringVariableCandidate(RecurringExpenseCandidatePayload item) {
        return !isSubscriptionCandidate(item) && !isFixedExpenseCandidate(item);
    }

    private String recurringText(RecurringExpenseCandidatePayload item) {
        return (item.title() + " " + item.categoryGroupName() + " " + item.categoryDetailName() + " " + item.paymentMethodName()).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
            insights.add("비교 기간에는 지출이 있었지만 선택 기간에는 지출 데이터가 부족합니다. 누락된 거래가 있는지 먼저 확인하세요.");
        } else {
            insights.add("선택 기간의 지출은 비교 기간 대비 " + formatSignedWon(delta) + "(" + formatPercentChange(delta, compareExpense) + ")입니다.");
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
                .forEach(item -> insights.add(item.label() + " 카테고리 지출 변화 " + formatSignedWon(item.delta()) + "."));
        return insights;
    }

    private List<PaymentSummaryPayload> buildExpensePaymentBreakdown(List<ExpenseEntryPayload> entries) {
        Map<String, PaymentAccumulator> groups = new LinkedHashMap<>();
        for (ExpenseEntryPayload entry : entries) {
            String paymentName = aiText.hasText(entry.paymentMethodName()) ? entry.paymentMethodName() : "결제수단 미확인";
            groups.computeIfAbsent(paymentName, PaymentAccumulator::new).add(entry.amount());
        }
        return groups.values().stream()
                .map(PaymentAccumulator::toPayload)
                .sorted(Comparator
                        .comparing(PaymentSummaryPayload::totalAmount, Comparator.reverseOrder())
                        .thenComparing(PaymentSummaryPayload::entryCount, Comparator.reverseOrder()))
                .toList();
    }

    private String buildTopPaymentMethodInsight(List<PaymentSummaryPayload> expensePayments, BigDecimal totalExpense) {
        if (expensePayments.isEmpty()) {
            return "지출 결제수단 데이터가 부족해 주요 결제수단을 판단할 수 없습니다.";
        }
        PaymentSummaryPayload top = expensePayments.get(0);
        return "지출 기준 결제수단 1위는 " + top.paymentMethodName() + "이며 " + formatWon(top.totalAmount()) + "(" + top.entryCount() + "건, 전체 지출의 " + formatPercentShare(top.totalAmount(), totalExpense) + ")입니다.";
    }

    private String formatPercentShare(BigDecimal amount, BigDecimal total) {
        BigDecimal safeTotal = nullToZero(total);
        if (safeTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return "비중 산정 불가";
        }
        return nullToZero(amount)
                .multiply(BigDecimal.valueOf(100))
                .divide(safeTotal, 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private Map<String, BigDecimal> categoryAmountMap(List<CategoryBreakdownItemResponse> items) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (CategoryBreakdownItemResponse item : items) {
            result.put(categoryLabel(item.groupName(), item.detailName()), nullToZero(item.totalAmount()));
        }
        return result;
    }

    private String categoryLabel(String groupName, String detailName) {
        if (aiText.hasText(detailName)) {
            return aiText.safeText(groupName) + " / " + detailName;
        }
        return aiText.safeText(groupName);
    }

    private void appendSentences(StringBuilder report, List<String> sentences) {
        for (String sentence : sentences) {
            if (aiText.hasText(sentence)) {
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
        return aiText.hasText(value) ? List.of(value) : List.of();
    }

    private String firstNonBlank(String primary, String fallback) {
        return aiText.hasText(primary) ? primary : aiText.safeText(fallback);
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
            return nullToZero(delta).compareTo(BigDecimal.ZERO) == 0 ? "0.00%" : "N/A";
        }
        return nullToZero(delta)
                .multiply(BigDecimal.valueOf(100))
                .divide(safeBase, 2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
    private List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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



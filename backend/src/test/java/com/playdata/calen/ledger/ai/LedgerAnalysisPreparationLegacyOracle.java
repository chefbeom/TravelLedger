package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.adapter.in.ai.LedgerLocalReportMapper;
import com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisBoundaryMapper;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.*;

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
import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAiJobLifecyclePolicy;
import java.math.BigDecimal;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Frozen calendar and full-dataset query behavior, independent of the migrated workflow. */
@RequiredArgsConstructor
public class LedgerAnalysisPreparationLegacyOracle {
    private static final long MAX_CUSTOM_RANGE_DAYS = 366;
    private static final int TOP_EXPENSE_LIMIT = 20;
    private final StatisticsService statisticsService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerAiAnalysisTextSanitizer aiText = new LedgerAiAnalysisTextSanitizer();
    public AnalysisDataset buildDataset(Long userId, AnalysisPlan plan) {
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
        List<ExpenseEntryPayload> topExpenses = plan.comparisonPreset() == LedgerAiComparisonPreset.WEEKLY_DIGEST
                ? List.of()
                : ledgerEntryRepository.findTopExpenseEntriesForAiAnalysis(
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

    public AnalysisPlan resolvePlan(LedgerAiAnalysisRequest request) {
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
            return new AnalysisPlan(mode, ranges.periodType(), preset, ranges.primary(), ranges.comparison(), normalizeFocusPrompt(request.focusPrompt()));
        }
        LedgerAiAnalysisPeriod periodType = request.periodType() == null ? LedgerAiAnalysisPeriod.MONTH : request.periodType();
        return new AnalysisPlan(mode, periodType, null, resolvePeriodRange(periodType, anchor, request.from(), request.to()), null, normalizeFocusPrompt(request.focusPrompt()));
    }

    private String normalizeFocusPrompt(String focusPrompt) {
        return aiText.redactSensitiveText(focusPrompt, 500);
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
            case PREVIOUS_WEEK, WEEKLY_DIGEST -> {
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

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record DateRange(LocalDate from, LocalDate to) {
    }

    private record ComparisonRanges(LedgerAiAnalysisPeriod periodType, DateRange primary, DateRange comparison) {
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
}

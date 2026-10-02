package com.playdata.calen.ledger.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.domain.ComparisonUnit;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.CalendarSummaryItemResponse;
import com.playdata.calen.ledger.dto.CategoryBreakdownItemResponse;
import com.playdata.calen.ledger.dto.DashboardCardResponse;
import com.playdata.calen.ledger.dto.DashboardResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse;
import com.playdata.calen.ledger.dto.PeriodComparisonItemResponse;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatisticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final AppUserService appUserService;
    private final LedgerEntryService ledgerEntryService;
    private final LedgerEntryRepository ledgerEntryRepository;

    public OverviewResponse getOverview(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        validateRange(from, to);
        LedgerEntryRepository.LedgerAmountAggregate aggregate = ledgerEntryRepository.aggregateAmountsByOwnerIdAndDateRange(
                userId,
                from,
                to,
                EntryType.INCOME,
                EntryType.EXPENSE
        );
        return buildOverview(from, to, aggregate);
    }

    public List<CalendarSummaryItemResponse> getCalendar(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        validateRange(from, to);
        List<LedgerEntryRepository.DailyAmountAggregate> dailyAmounts = ledgerEntryRepository.aggregateDailyAmountsByOwnerIdAndDateRange(
                userId,
                from,
                to,
                EntryType.INCOME,
                EntryType.EXPENSE
        );

        return buildCalendar(from, to, dailyAmounts);
    }

    private List<CalendarSummaryItemResponse> buildCalendar(
            LocalDate from, LocalDate to, List<LedgerEntryRepository.DailyAmountAggregate> dailyAmounts
    ) {
        List<CalendarSummaryItemResponse> responses = new ArrayList<>();
        int rowIndex = 0;
        while (rowIndex < dailyAmounts.size() && dailyAmounts.get(rowIndex).getEntryDate().isBefore(from)) {
            rowIndex++;
        }
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LedgerEntryRepository.DailyAmountAggregate dailyAmount = null;
            if (rowIndex < dailyAmounts.size() && dailyAmounts.get(rowIndex).getEntryDate().equals(cursor)) {
                dailyAmount = dailyAmounts.get(rowIndex);
                rowIndex += 1;
            }

            BigDecimal income = dailyAmount == null ? ZERO : nullToZero(dailyAmount.getIncome());
            BigDecimal expense = dailyAmount == null ? ZERO : nullToZero(dailyAmount.getExpense());
            long count = dailyAmount == null ? 0 : dailyAmount.getEntryCount();
            responses.add(new CalendarSummaryItemResponse(
                    cursor,
                    income,
                    expense,
                    income.subtract(expense),
                    count
            ));
            cursor = cursor.plusDays(1);
        }
        return responses;
    }

    public List<CategoryBreakdownItemResponse> getCategoryBreakdown(Long userId, LocalDate from, LocalDate to, EntryType entryType) {
        appUserService.getRequiredUser(userId);
        validateRange(from, to);
        return ledgerEntryRepository.aggregateCategoryBreakdownByOwnerIdAndDateRange(userId, from, to, entryType).stream()
                .map(row -> new CategoryBreakdownItemResponse(
                        row.getGroupName(),
                        row.getDetailName(),
                        nullToZero(row.getTotalAmount()),
                        row.getEntryCount()
                ))
                .toList();
    }

    public List<PaymentBreakdownItemResponse> getPaymentBreakdown(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        validateRange(from, to);
        return ledgerEntryRepository.aggregatePaymentBreakdownByOwnerIdAndDateRange(userId, from, to, EntryType.EXPENSE).stream()
                .map(row -> new PaymentBreakdownItemResponse(
                        row.getPaymentMethodName(),
                        row.getKind(),
                        nullToZero(row.getTotalAmount()),
                        row.getEntryCount()
                ))
                .toList();
    }

    public List<PeriodComparisonItemResponse> compare(Long userId, LocalDate anchorDate, ComparisonUnit unit, int periods) {
        appUserService.getRequiredUser(userId);
        if (periods < 1 || periods > 24) {
            throw new BadRequestException("periods must be between 1 and 24.");
        }

        LocalDate anchor = anchorDate == null ? LocalDate.now() : anchorDate;
        List<PeriodWindow> windows = buildWindows(anchor, unit, periods);
        List<LedgerEntryRepository.DailyAmountAggregate> dailyAmounts = ledgerEntryRepository.aggregateDailyAmountsByOwnerIdAndDateRange(
                userId,
                windows.get(0).startDate(),
                windows.get(windows.size() - 1).endDate(),
                EntryType.INCOME,
                EntryType.EXPENSE
        );

        return windows.stream()
                .map(window -> {
                    PeriodTotals totals = sumDailyAmounts(dailyAmounts, window.startDate(), window.endDate());
                    return new PeriodComparisonItemResponse(
                            window.label(),
                            window.startDate(),
                            window.endDate(),
                            totals.income(),
                            totals.expense(),
                            totals.income().subtract(totals.expense())
                    );
                })
                .toList();
    }

    public DashboardResponse getDashboard(Long userId, LocalDate anchorDate) {
        appUserService.getRequiredUser(userId);
        LocalDate anchor = anchorDate == null ? LocalDate.now() : anchorDate;
        LocalDate weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = anchor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate monthStart = anchor.withDayOfMonth(1);
        LocalDate monthEnd = anchor.withDayOfMonth(anchor.lengthOfMonth());
        LocalDate yearStart = anchor.withDayOfYear(1);
        LocalDate yearEnd = anchor.withDayOfYear(anchor.lengthOfYear());
        List<PeriodWindow> months = buildWindows(anchor, ComparisonUnit.MONTH, 12);
        LocalDate from = java.util.stream.Stream.of(yearStart, weekStart, months.get(0).startDate())
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate to = yearEnd.isAfter(weekEnd) ? yearEnd : weekEnd;
        // One bounded daily aggregate supplies all overlapping dashboard periods.
        List<LedgerEntryRepository.DailyAmountAggregate> dailyAmounts =
                ledgerEntryRepository.aggregateDailyAmountsByOwnerIdAndDateRange(
                        userId, from, to, EntryType.INCOME, EntryType.EXPENSE);

        List<DashboardCardResponse> quickStats = List.of(
                new DashboardCardResponse("day", "오늘", overviewFromDays(anchor, anchor, dailyAmounts)),
                new DashboardCardResponse("week", "이번 주", overviewFromDays(weekStart, weekEnd, dailyAmounts)),
                new DashboardCardResponse("month", "이번 달", overviewFromDays(monthStart, monthEnd, dailyAmounts)),
                new DashboardCardResponse("year", "올해", overviewFromDays(yearStart, yearEnd, dailyAmounts))
        );
        List<PeriodComparisonItemResponse> monthlyComparison = months.stream().map(window -> {
            PeriodTotals totals = sumDailyAmounts(dailyAmounts, window.startDate(), window.endDate());
            return new PeriodComparisonItemResponse(window.label(), window.startDate(), window.endDate(),
                    totals.income(), totals.expense(), totals.income().subtract(totals.expense()));
        }).toList();

        return new DashboardResponse(
                anchor,
                quickStats,
                buildCalendar(monthStart, monthEnd, dailyAmounts),
                getCategoryBreakdown(userId, monthStart, monthEnd, EntryType.EXPENSE),
                getPaymentBreakdown(userId, monthStart, monthEnd),
                monthlyComparison,
                ledgerEntryService.getRecentEntries(userId)
        );
    }

    private OverviewResponse overviewFromDays(LocalDate from, LocalDate to,
            List<LedgerEntryRepository.DailyAmountAggregate> days) {
        PeriodTotals totals = sumDailyAmounts(days, from, to);
        long count = days.stream().filter(row -> !row.getEntryDate().isBefore(from) && !row.getEntryDate().isAfter(to))
                .mapToLong(LedgerEntryRepository.DailyAmountAggregate::getEntryCount).sum();
        return new OverviewResponse(from, to, totals.income(), totals.expense(),
                totals.income().subtract(totals.expense()), count);
    }

    private OverviewResponse buildOverview(LocalDate from, LocalDate to, LedgerEntryRepository.LedgerAmountAggregate aggregate) {
        BigDecimal income = aggregate == null ? ZERO : nullToZero(aggregate.getIncome());
        BigDecimal expense = aggregate == null ? ZERO : nullToZero(aggregate.getExpense());
        long entryCount = aggregate == null ? 0 : aggregate.getEntryCount();
        return new OverviewResponse(
                from,
                to,
                income,
                expense,
                income.subtract(expense),
                entryCount
        );
    }

    private PeriodTotals sumDailyAmounts(
            List<LedgerEntryRepository.DailyAmountAggregate> dailyAmounts,
            LocalDate from,
            LocalDate to
    ) {
        BigDecimal income = ZERO;
        BigDecimal expense = ZERO;
        for (LedgerEntryRepository.DailyAmountAggregate row : dailyAmounts) {
            if (row.getEntryDate().isBefore(from) || row.getEntryDate().isAfter(to)) {
                continue;
            }
            income = income.add(nullToZero(row.getIncome()));
            expense = expense.add(nullToZero(row.getExpense()));
        }
        return new PeriodTotals(income, expense);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from must be before or equal to to.");
        }
    }

    private List<PeriodWindow> buildWindows(LocalDate anchor, ComparisonUnit unit, int periods) {
        List<PeriodWindow> windows = new ArrayList<>();
        for (int index = periods - 1; index >= 0; index--) {
            switch (unit) {
                case DAY -> {
                    LocalDate day = anchor.minusDays(index);
                    windows.add(new PeriodWindow(day, day, day.format(DateTimeFormatter.ofPattern("MM-dd"))));
                }
                case WEEK -> {
                    LocalDate weekAnchor = anchor.minusWeeks(index);
                    LocalDate start = weekAnchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    LocalDate end = weekAnchor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                    windows.add(new PeriodWindow(start, end, start.format(DateTimeFormatter.ofPattern("MM/dd")) + " ~ " + end.format(DateTimeFormatter.ofPattern("MM/dd"))));
                }
                case MONTH -> {
                    YearMonth yearMonth = YearMonth.from(anchor).minusMonths(index);
                    windows.add(new PeriodWindow(yearMonth.atDay(1), yearMonth.atEndOfMonth(), yearMonth.toString()));
                }
                case QUARTER -> {
                    LocalDate quarterAnchor = anchor.minusMonths((long) index * 3);
                    int quarter = ((quarterAnchor.getMonthValue() - 1) / 3) + 1;
                    int firstMonth = ((quarter - 1) * 3) + 1;
                    LocalDate start = LocalDate.of(quarterAnchor.getYear(), firstMonth, 1);
                    LocalDate end = start.plusMonths(2).withDayOfMonth(start.plusMonths(2).lengthOfMonth());
                    windows.add(new PeriodWindow(start, end, quarterAnchor.getYear() + " Q" + quarter));
                }
                case YEAR -> {
                    LocalDate year = anchor.minusYears(index);
                    LocalDate start = year.withDayOfYear(1);
                    LocalDate end = year.withDayOfYear(year.lengthOfYear());
                    windows.add(new PeriodWindow(start, end, String.valueOf(year.getYear())));
                }
            }
        }
        return windows;
    }

    private record PeriodWindow(LocalDate startDate, LocalDate endDate, String label) {
    }

    private record PeriodTotals(BigDecimal income, BigDecimal expense) {
    }
}

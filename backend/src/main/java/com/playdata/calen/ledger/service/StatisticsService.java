package com.playdata.calen.ledger.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.ComparisonUnit;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.CalendarSummaryItemResponse;
import com.playdata.calen.ledger.dto.CategoryBreakdownItemResponse;
import com.playdata.calen.ledger.dto.DashboardCardResponse;
import com.playdata.calen.ledger.dto.DashboardResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse;
import com.playdata.calen.ledger.dto.PeriodComparisonItemResponse;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatisticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final LedgerEntryService ledgerEntryService;

    public OverviewResponse getOverview(Long userId, LocalDate from, LocalDate to) {
        List<LedgerEntry> entries = ledgerEntryService.loadRawEntries(userId, from, to);
        return buildOverview(from, to, entries);
    }

    public List<CalendarSummaryItemResponse> getCalendar(Long userId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<LedgerEntry> entries = ledgerEntryService.loadRawEntries(userId, from, to);
        Map<LocalDate, List<LedgerEntry>> grouped = new LinkedHashMap<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            grouped.put(cursor, new ArrayList<>());
            cursor = cursor.plusDays(1);
        }
        entries.forEach(entry -> grouped.computeIfAbsent(entry.getEntryDate(), ignored -> new ArrayList<>()).add(entry));

        return grouped.entrySet().stream()
                .map(entry -> {
                    BigDecimal income = sumByType(entry.getValue(), EntryType.INCOME);
                    BigDecimal expense = sumByType(entry.getValue(), EntryType.EXPENSE);
                    return new CalendarSummaryItemResponse(
                            entry.getKey(),
                            income,
                            expense,
                            income.subtract(expense),
                            entry.getValue().size()
                    );
                })
                .toList();
    }

    public List<CategoryBreakdownItemResponse> getCategoryBreakdown(Long userId, LocalDate from, LocalDate to, EntryType entryType) {
        validateRange(from, to);
        List<LedgerEntry> entries = ledgerEntryService.loadRawEntries(userId, from, to).stream()
                .filter(entry -> entryType == null || entry.getEntryType() == entryType)
                .toList();

        Map<String, List<LedgerEntry>> grouped = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            String detailName = entry.getCategoryDetail() != null ? entry.getCategoryDetail().getName() : "미분류";
            String key = entry.getCategoryGroup().getName() + "::" + detailName;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        return grouped.values().stream()
                .map(group -> new CategoryBreakdownItemResponse(
                        group.get(0).getCategoryGroup().getName(),
                        group.get(0).getCategoryDetail() != null ? group.get(0).getCategoryDetail().getName() : "미분류",
                        group.stream().map(LedgerEntry::getAmount).reduce(ZERO, BigDecimal::add),
                        group.size()
                ))
                .sorted(Comparator.comparing(CategoryBreakdownItemResponse::totalAmount).reversed())
                .toList();
    }

    public List<PaymentBreakdownItemResponse> getPaymentBreakdown(Long userId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<LedgerEntry> entries = ledgerEntryService.loadRawEntries(userId, from, to);

        Map<Long, List<LedgerEntry>> grouped = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            grouped.computeIfAbsent(entry.getPaymentMethod().getId(), ignored -> new ArrayList<>()).add(entry);
        }

        return grouped.values().stream()
                .map(group -> new PaymentBreakdownItemResponse(
                        group.get(0).getPaymentMethod().getName(),
                        group.get(0).getPaymentMethod().getKind(),
                        group.stream().map(LedgerEntry::getAmount).reduce(ZERO, BigDecimal::add),
                        group.size()
                ))
                .sorted(Comparator.comparing(PaymentBreakdownItemResponse::totalAmount).reversed())
                .toList();
    }

    public List<PeriodComparisonItemResponse> compare(Long userId, LocalDate anchorDate, ComparisonUnit unit, int periods) {
        if (periods < 1 || periods > 24) {
            throw new BadRequestException("periods는 1 이상 24 이하로 입력해 주세요.");
        }

        LocalDate anchor = anchorDate == null ? LocalDate.now() : anchorDate;
        List<PeriodWindow> windows = buildWindows(anchor, unit, periods);
        List<LedgerEntry> entries = ledgerEntryService.loadRawEntries(userId, windows.get(0).startDate(), windows.get(windows.size() - 1).endDate());

        return windows.stream()
                .map(window -> {
                    List<LedgerEntry> periodEntries = entries.stream()
                            .filter(entry -> !entry.getEntryDate().isBefore(window.startDate()) && !entry.getEntryDate().isAfter(window.endDate()))
                            .toList();
                    BigDecimal income = sumByType(periodEntries, EntryType.INCOME);
                    BigDecimal expense = sumByType(periodEntries, EntryType.EXPENSE);
                    return new PeriodComparisonItemResponse(
                            window.label(),
                            window.startDate(),
                            window.endDate(),
                            income,
                            expense,
                            income.subtract(expense)
                    );
                })
                .toList();
    }

    public DashboardResponse getDashboard(Long userId, LocalDate anchorDate) {
        LocalDate anchor = anchorDate == null ? LocalDate.now() : anchorDate;
        LocalDate weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = anchor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate monthStart = anchor.withDayOfMonth(1);
        LocalDate monthEnd = anchor.withDayOfMonth(anchor.lengthOfMonth());
        LocalDate yearStart = anchor.withDayOfYear(1);
        LocalDate yearEnd = anchor.withDayOfYear(anchor.lengthOfYear());

        List<DashboardCardResponse> quickStats = List.of(
                new DashboardCardResponse("day", "오늘", getOverview(userId, anchor, anchor)),
                new DashboardCardResponse("week", "이번 주", getOverview(userId, weekStart, weekEnd)),
                new DashboardCardResponse("month", "이번 달", getOverview(userId, monthStart, monthEnd)),
                new DashboardCardResponse("year", "올해", getOverview(userId, yearStart, yearEnd))
        );

        return new DashboardResponse(
                anchor,
                quickStats,
                getCalendar(userId, monthStart, monthEnd),
                getCategoryBreakdown(userId, monthStart, monthEnd, EntryType.EXPENSE),
                getPaymentBreakdown(userId, monthStart, monthEnd),
                compare(userId, anchor, ComparisonUnit.MONTH, 12),
                ledgerEntryService.getRecentEntries(userId)
        );
    }

    private OverviewResponse buildOverview(LocalDate from, LocalDate to, List<LedgerEntry> entries) {
        BigDecimal income = sumByType(entries, EntryType.INCOME);
        BigDecimal expense = sumByType(entries, EntryType.EXPENSE);

        return new OverviewResponse(
                from,
                to,
                income,
                expense,
                income.subtract(expense),
                entries.size()
        );
    }

    private BigDecimal sumByType(List<LedgerEntry> entries, EntryType type) {
        return entries.stream()
                .filter(entry -> entry.getEntryType() == type)
                .map(LedgerEntry::getAmount)
                .reduce(ZERO, BigDecimal::add);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from, to 날짜를 함께 전달해 주세요.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from 날짜는 to 날짜보다 앞서야 합니다.");
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
}

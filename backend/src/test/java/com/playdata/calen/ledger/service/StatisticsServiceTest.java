package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.DashboardResponse;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatisticsServiceTest {
    @ParameterizedTest
    @CsvSource({"2026-01-01,2025-12-29,2026-01-04", "2026-12-31,2026-12-28,2027-01-03"})
    void dashboardReusesDailyTotalsAcrossYearBoundary(String anchorText, String startText, String endText) {
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        LedgerEntryService entries = mock(LedgerEntryService.class);
        StatisticsService service = new StatisticsService(mock(AppUserService.class), entries, repository);
        LocalDate anchor = LocalDate.parse(anchorText);
        LocalDate start = LocalDate.parse(startText);
        LocalDate end = LocalDate.parse(endText);
        var rows = List.of(day(start, "10", "1"), day(anchor, "20", "2"), day(end, "30", "3"));
        when(repository.aggregateDailyAmountsByOwnerIdAndDateRange(eq(7L), any(), any(), any(), any()))
                .thenReturn(rows);

        DashboardResponse response = service.getDashboard(7L, anchor);

        assertThat(response.quickStats().get(0).overview().income()).isEqualByComparingTo("20");
        assertThat(response.quickStats().get(1).overview().income()).isEqualByComparingTo("60");
        assertThat(response.quickStats().get(1).overview().entryCount()).isEqualTo(3);
        assertThat(response.quickStats().get(1).overview().from()).isEqualTo(start);
        assertThat(response.quickStats().get(1).overview().to()).isEqualTo(end);
        assertThat(response.quickStats().get(2).overview().income()).isEqualByComparingTo(
                anchor.getMonthValue() == 1 ? "50" : "30");
        assertThat(response.quickStats().get(3).overview().income()).isEqualByComparingTo(
                anchor.getMonthValue() == 1 ? "50" : "30");
        assertThat(response.calendar()).hasSize(31);
        assertThat(response.calendar().stream().filter(day -> day.date().equals(anchor)).findFirst().orElseThrow().income())
                .isEqualByComparingTo("20");
        assertThat(response.monthlyComparison()).hasSize(12);
        verify(repository, times(1)).aggregateDailyAmountsByOwnerIdAndDateRange(
                eq(7L), any(), eq(anchor.getMonthValue() == 1 ? LocalDate.of(2026, 12, 31) : end),
                eq(EntryType.INCOME), eq(EntryType.EXPENSE));
        verify(repository, never()).aggregateAmountsByOwnerIdAndDateRange(any(), any(), any(), any(), any());
    }

    private LedgerEntryRepository.DailyAmountAggregate day(LocalDate date, String income, String expense) {
        LedgerEntryRepository.DailyAmountAggregate row = mock(LedgerEntryRepository.DailyAmountAggregate.class);
        when(row.getEntryDate()).thenReturn(date);
        when(row.getIncome()).thenReturn(new BigDecimal(income));
        when(row.getExpense()).thenReturn(new BigDecimal(expense));
        when(row.getEntryCount()).thenReturn(1L);
        return row;
    }
}

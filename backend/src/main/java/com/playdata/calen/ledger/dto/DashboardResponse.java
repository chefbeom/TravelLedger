package com.playdata.calen.ledger.dto;

import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
        LocalDate anchorDate,
        List<DashboardCardResponse> quickStats,
        List<CalendarSummaryItemResponse> calendar,
        List<CategoryBreakdownItemResponse> expenseBreakdown,
        List<PaymentBreakdownItemResponse> paymentBreakdown,
        List<PeriodComparisonItemResponse> monthlyComparison,
        List<LedgerEntryResponse> recentEntries
) {
}

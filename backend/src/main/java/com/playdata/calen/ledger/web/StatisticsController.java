package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.ComparisonUnit;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.CalendarSummaryItemResponse;
import com.playdata.calen.ledger.dto.CategoryBreakdownItemResponse;
import com.playdata.calen.ledger.dto.DashboardResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.dto.PaymentBreakdownItemResponse;
import com.playdata.calen.ledger.dto.PeriodComparisonItemResponse;
import com.playdata.calen.ledger.service.StatisticsService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate
    ) {
        return statisticsService.getDashboard(currentUser.userId(), anchorDate);
    }

    @GetMapping("/statistics/overview")
    public OverviewResponse overview(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return statisticsService.getOverview(currentUser.userId(), from, to);
    }

    @GetMapping("/statistics/calendar")
    public List<CalendarSummaryItemResponse> calendar(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return statisticsService.getCalendar(currentUser.userId(), from, to);
    }

    @GetMapping("/statistics/category-breakdown")
    public List<CategoryBreakdownItemResponse> categoryBreakdown(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryType entryType
    ) {
        return statisticsService.getCategoryBreakdown(currentUser.userId(), from, to, entryType);
    }

    @GetMapping("/statistics/payment-breakdown")
    public List<PaymentBreakdownItemResponse> paymentBreakdown(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return statisticsService.getPaymentBreakdown(currentUser.userId(), from, to);
    }

    @GetMapping("/statistics/compare")
    public List<PeriodComparisonItemResponse> compare(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate,
            @RequestParam(defaultValue = "MONTH") ComparisonUnit unit,
            @RequestParam(defaultValue = "12") int periods
    ) {
        return statisticsService.compare(currentUser.userId(), anchorDate, unit, periods);
    }
}

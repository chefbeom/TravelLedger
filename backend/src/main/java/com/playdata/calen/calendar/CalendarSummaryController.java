package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.EntryType;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/summary") @RequiredArgsConstructor
public class CalendarSummaryController {
    private final CalendarSummaryService summaries;

    @GetMapping("/ledger") public ResponseEntity<List<CalendarSummaryService.MoneyDay>> ledger(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryType type, @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long paymentMethodId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.ledger(user.userId(), from, to, type, categoryId, paymentMethodId));
    }
    @GetMapping("/places") public ResponseEntity<List<CalendarSummaryService.CountDay>> places(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long collectionId, @RequestParam(required = false) Long planId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.places(user.userId(), from, to, collectionId, planId));
    }
    @GetMapping("/ledger-categories") public ResponseEntity<List<CalendarSummaryService.CategoryExpense>> ledgerCategories(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryType type, @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long paymentMethodId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.expenseCategories(user.userId(), from, to, type, categoryId, paymentMethodId));
    }
    @GetMapping("/travel") public ResponseEntity<CalendarSummaryService.TravelSummary> travel(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long planId, @RequestParam(required = false) Long collectionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.travel(user.userId(), from, to, planId, collectionId));
    }
    @GetMapping("/events") public ResponseEntity<List<CalendarSummaryService.EventSummary>> events(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.personalEvents(user.userId(), from, to));
    }
}

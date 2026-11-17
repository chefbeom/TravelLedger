package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/shared") @RequiredArgsConstructor
public class CalendarSharedController {
    private final CalendarSharedService shares;
    @GetMapping("/options/{type}") public ResponseEntity<CalendarSharedService.OptionPage> options(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String type, @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shares.options(user.userId(), type, page));
    }
    @PutMapping("/selections/{type}/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void select(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String type, @PathVariable Long id, @RequestBody Selection selection) {
        shares.setSelected(user.userId(), type, id, selection.selected());
    }
    @GetMapping("/summary/{type}/{id}") public ResponseEntity<CalendarSharedService.SharedSummary> summary(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String type, @PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shares.summary(user.userId(), type, id, from, to));
    }
    @GetMapping("/day/{type}/{id}") public ResponseEntity<CalendarSharedService.SharedDay> day(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String type, @PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shares.day(user.userId(), type, id, date));
    }
    public record Selection(boolean selected) { }
}

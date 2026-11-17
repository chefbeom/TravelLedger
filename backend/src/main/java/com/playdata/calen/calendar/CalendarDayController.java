package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/day") @RequiredArgsConstructor
public class CalendarDayController {
    private final CalendarDayService days;
    @GetMapping("/place-source/{placeId}") public ResponseEntity<?> placeSource(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long placeId, @RequestParam(required = false) Long visitId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.placeSource(user.userId(), placeId, visitId));
    }
    @GetMapping("/ledger") public ResponseEntity<?> ledger(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.ledger(user.userId(), date));
    }
    @GetMapping("/places") public ResponseEntity<?> places(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) Long collectionId, @RequestParam(required = false) Long planId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.places(user.userId(), date, collectionId, planId));
    }
    @GetMapping("/travel-records") public ResponseEntity<?> records(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) Long planId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.travelRecords(user.userId(), date, planId));
    }
    @GetMapping("/routes") public ResponseEntity<?> routes(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) Long planId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(days.routes(user.userId(), date, planId));
    }
}

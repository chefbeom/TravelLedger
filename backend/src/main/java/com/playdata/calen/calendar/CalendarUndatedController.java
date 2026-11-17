package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/undated") @RequiredArgsConstructor
public class CalendarUndatedController {
    private final CalendarUndatedService undated;
    @GetMapping public ResponseEntity<CalendarUndatedService.Result> list(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(defaultValue = "0") int placePage, @RequestParam(defaultValue = "0") int tripPage) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(undated.list(user.userId(), placePage, tripPage));
    }
}

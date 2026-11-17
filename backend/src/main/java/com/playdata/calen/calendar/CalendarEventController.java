package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/events") @RequiredArgsConstructor
public class CalendarEventController {
    private final CalendarEventService events;

    @GetMapping public ResponseEntity<List<CalendarEventService.View>> list(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.list(user.userId(), from, to));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CalendarEventService.View create(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody CalendarEventService.Draft draft) {
        return events.create(user.userId(), draft);
    }
    @PutMapping("/{id}") public CalendarEventService.View update(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestBody CalendarEventService.Draft draft) {
        return events.update(user.userId(), id, draft);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestParam Long version) {
        events.delete(user.userId(), id, version);
    }
}

package com.playdata.calen.calendar;

import com.playdata.calen.account.security.AppUserPrincipal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import com.playdata.calen.recordmap.application.port.in.ReadRecordMapUseCase;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Filter;
import com.playdata.calen.recordmap.adapter.support.RecordMapReadMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrated-calendar/shared-map") @RequiredArgsConstructor
public class CalendarMapShareController {
    private final CalendarMapShareService maps;
    private final TravelMediaStorageService storage;
    private final ReadRecordMapUseCase reads;
    public record Link(String token) { }
    public record Selection(boolean selected) { }
    @PostMapping("/links") @ResponseStatus(HttpStatus.NO_CONTENT) public void add(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody Link link) { maps.add(user.userId(), link.token()); }
    @GetMapping("/options") public ResponseEntity<?> options(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.options(user.userId(), page));
    }
    @PutMapping("/selections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void select(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestBody Selection selection) { maps.select(user.userId(), id, selection.selected()); }
    @GetMapping("/{id}/summary") public ResponseEntity<?> summary(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.summary(user.userId(), id, from, to));
    }
    @GetMapping("/{id}/day") public ResponseEntity<?> day(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.day(user.userId(), id, date));
    }
    @GetMapping("/{id}/{view:overview|map}") public ResponseEntity<?> map(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @PathVariable String view,
            @RequestParam(defaultValue = "ALL") String state, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecordMapReadMapper.calendarResponse(
                reads.calendarShared(user.userId(), id, new Filter(state, from, to, page, q, size, "map".equals(view))), id));
    }
    @GetMapping("/{id}/place-source/{placeId}") public ResponseEntity<?> place(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @PathVariable Long placeId,
            @RequestParam String kind, @RequestParam Long recordId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.placeSource(user.userId(), id, placeId, kind, recordId, date));
    }
    @GetMapping("/{id}/photos/{kind}/{photoId}") public ResponseEntity<Resource> photo(@AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable Long id, @PathVariable String kind, @PathVariable Long photoId,
            @RequestParam(defaultValue = "false") boolean thumbnail, @RequestParam(required = false) Integer width) {
        var source = maps.photo(user.userId(), id, kind, photoId);
        var prepared = thumbnail ? storage.loadThumbnail(source.path(), source.contentType(), width) : null;
        Resource resource = prepared == null ? storage.loadAsResource(source.path()) : prepared.resource();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(source.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(prepared == null ? source.contentType() : prepared.contentType())).body(resource);
    }
}

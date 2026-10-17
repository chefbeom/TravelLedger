package com.playdata.calen.recordmap.adapter.in.web;

import static com.playdata.calen.recordmap.adapter.support.RecordMapWriteMapper.*;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import com.playdata.calen.recordmap.service.*;
import com.playdata.calen.recordmap.application.port.in.WriteRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.in.ReadRecordMapUseCase;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Filter;
import com.playdata.calen.recordmap.adapter.support.RecordMapReadMapper;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/record-map") @RequiredArgsConstructor
public class RecordMapController {
    private final ReadRecordMapUseCase reads;
    private final WriteRecordMapUseCase writes;
    private final RecordMapMediaService media;
    private final RecordMapShareService sharing;
    private final TravelMediaStorageService storage;
    private final RecordMapLocationSearchService locations;

    @GetMapping({"/overview", "/map"})
    public ResponseEntity<Overview> overview(@AuthenticationPrincipal AppUserPrincipal user,
        @RequestParam(required=false) Long collectionId, @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="100") int size, jakarta.servlet.http.HttpServletRequest request) {
        var result = reads.owned(user.userId(), collectionId, new Filter(state, from, to, page, q, size, request.getRequestURI().endsWith("/map")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecordMapReadMapper.response(result, null));
    }
    @GetMapping("/locations/search")
    public ResponseEntity<List<LocationView>> searchLocations(@RequestParam String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locations.search(q));
    }
    @PostMapping("/places")
    public Map<String, Long> createPlace(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody PlaceRequest body) {
        return Map.of("id", writes.savePlace(user.userId(), null, draft(body)));
    }
    @PutMapping("/places/{id}")
    public Map<String, Long> updatePlace(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody PlaceRequest body) {
        return Map.of("id", writes.savePlace(user.userId(), id, draft(body)));
    }
    @DeleteMapping("/places/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlace(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { writes.deletePlace(user.userId(), id); }

    @PostMapping("/collections")
    public CollectionView createCollection(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody CollectionRequest body) {
        return response(writes.saveCollection(user.userId(), null, draft(body)));
    }
    @PutMapping("/collections/{id}")
    public CollectionView updateCollection(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody CollectionRequest body) {
        return response(writes.saveCollection(user.userId(), id, draft(body)));
    }
    @DeleteMapping("/collections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCollection(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { writes.deleteCollection(user.userId(), id); }

    @GetMapping("/places/{id}/visits")
    public ResponseEntity<List<VisitView>> visits(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecordMapReadMapper.visits(reads.placeVisits(user.userId(), id, page), null));
    }
    @PostMapping("/places/{placeId}/visits")
    public Map<String, Long> createVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long placeId, @Valid @RequestBody VisitRequest body) {
        return Map.of("id", writes.saveVisit(user.userId(), placeId, null, draft(body)));
    }
    @PutMapping("/places/{placeId}/visits/{id}")
    public Map<String, Long> updateVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long placeId, @PathVariable Long id, @Valid @RequestBody VisitRequest body) {
        return Map.of("id", writes.saveVisit(user.userId(), placeId, id, draft(body)));
    }
    @DeleteMapping("/visits/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { writes.deleteVisit(user.userId(), id); }
    @PostMapping(value="/visits/{id}/photos", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoView upload(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return media.upload(user.userId(), id, file);
    }
    @DeleteMapping("/photos/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { media.delete(user.userId(), id); }
    @GetMapping("/photos/{id}/content")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id,
        @RequestParam(defaultValue="false") boolean thumbnail, @RequestParam(required=false) Integer width) {
        return content(media.getOwned(user.userId(), id), thumbnail, width);
    }

    @GetMapping("/travel-candidates")
    public org.springframework.data.domain.Page<TravelCandidate> candidates(@AuthenticationPrincipal AppUserPrincipal user,
        @RequestParam(defaultValue="0") int page) { return RecordMapReadMapper.candidates(reads.travelCandidates(user.userId(), page)); }
    @PostMapping("/travel-imports")
    public Map<String, Long> importTravel(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody TravelImportRequest body) {
        return Map.of("id", writes.importTravel(user.userId(), draft(body)));
    }

    @GetMapping("/collections/{id}/shares")
    public List<ShareView> shares(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return sharing.list(user.userId(), id); }
    @PostMapping("/collections/{id}/shares")
    public ShareView createShare(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody ShareRequest body) {
        return sharing.create(user.userId(), id, body);
    }
    @DeleteMapping("/shares/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { sharing.revoke(user.userId(), id); }

    @GetMapping({"/public/{token}", "/public/{token}/map"})
    public ResponseEntity<Overview> publicOverview(@PathVariable String token, @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="100") int size, jakarta.servlet.http.HttpServletRequest request) {
        var result = reads.shared(token, new Filter(state, from, to, page, q, size, request.getRequestURI().endsWith("/map")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecordMapReadMapper.response(result, token));
    }
    @GetMapping("/public/{token}/photos/{id}/content")
    public ResponseEntity<Resource> sharedPhoto(@PathVariable String token, @PathVariable Long id,
        @RequestParam(defaultValue="false") boolean thumbnail, @RequestParam(required=false) Integer width) {
        return content(media.getShared(token, id), thumbnail, width);
    }
    @GetMapping("/public/{token}/travel-photos/{id}/content")
    public ResponseEntity<Resource> sharedTravelPhoto(@PathVariable String token, @PathVariable Long id,
        @RequestParam(defaultValue="false") boolean thumbnail, @RequestParam(required=false) Integer width) {
        return content(media.getSharedTravel(token, id), thumbnail, width);
    }
    private ResponseEntity<Resource> content(RecordMapMediaService.Media source, boolean thumbnail, Integer width) {
        var prepared = thumbnail ? storage.loadThumbnail(source.path(), source.contentType(), width) : null;
        Resource resource = prepared == null ? storage.loadAsResource(source.path()) : prepared.resource();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(source.fileName(), StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(prepared == null ? source.contentType() : prepared.contentType())).body(resource);
    }
}

package com.playdata.calen.recordmap.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import com.playdata.calen.recordmap.service.*;
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
    private final RecordMapService service;
    private final RecordMapMediaService media;
    private final RecordMapShareService sharing;
    private final TravelMediaStorageService storage;

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(@AuthenticationPrincipal AppUserPrincipal user,
        @RequestParam(required=false) Long collectionId, @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(user.userId(), collectionId, state, from, to, page));
    }
    @PostMapping("/places")
    public Map<String, Long> createPlace(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody PlaceRequest body) {
        return Map.of("id", service.savePlace(user.userId(), null, body));
    }
    @PutMapping("/places/{id}")
    public Map<String, Long> updatePlace(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody PlaceRequest body) {
        return Map.of("id", service.savePlace(user.userId(), id, body));
    }
    @DeleteMapping("/places/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlace(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { service.deletePlace(user.userId(), id); }

    @PostMapping("/collections")
    public CollectionView createCollection(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody CollectionRequest body) {
        return service.saveCollection(user.userId(), null, body);
    }
    @PutMapping("/collections/{id}")
    public CollectionView updateCollection(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody CollectionRequest body) {
        return service.saveCollection(user.userId(), id, body);
    }
    @DeleteMapping("/collections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCollection(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { service.deleteCollection(user.userId(), id); }

    @GetMapping("/places/{id}/visits")
    public ResponseEntity<List<VisitView>> visits(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.placeVisits(user.userId(), id, page));
    }
    @PostMapping("/places/{placeId}/visits")
    public Map<String, Long> createVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long placeId, @Valid @RequestBody VisitRequest body) {
        return Map.of("id", service.saveVisit(user.userId(), placeId, null, body));
    }
    @PutMapping("/places/{placeId}/visits/{id}")
    public Map<String, Long> updateVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long placeId, @PathVariable Long id, @Valid @RequestBody VisitRequest body) {
        return Map.of("id", service.saveVisit(user.userId(), placeId, id, body));
    }
    @DeleteMapping("/visits/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVisit(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { service.deleteVisit(user.userId(), id); }
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
        @RequestParam(defaultValue="0") int page) { return service.travelCandidates(user.userId(), page); }
    @PostMapping("/travel-imports")
    public Map<String, Long> importTravel(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody TravelImportRequest body) {
        return Map.of("id", service.importTravel(user.userId(), body));
    }

    @GetMapping("/collections/{id}/shares")
    public List<ShareView> shares(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return sharing.list(user.userId(), id); }
    @PostMapping("/collections/{id}/shares")
    public ShareView createShare(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody ShareRequest body) {
        return sharing.create(user.userId(), id, body);
    }
    @DeleteMapping("/shares/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { sharing.revoke(user.userId(), id); }

    @GetMapping("/public/{token}")
    public ResponseEntity<Overview> publicOverview(@PathVariable String token, @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sharedOverview(token, state, from, to, page));
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

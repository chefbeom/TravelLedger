package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelMapLinkMapper;
import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelMapLinkController {
    private final TravelMapLinksUseCase links;
    private final TravelMediaHttpResponses media;
    @PostMapping("/map-shares")
    public TravelMapShareLinkResponse create(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody TravelMapShareLinkRequest request) {
        return TravelMapLinkMapper.link(links.create(user.userId(), TravelMapLinkMapper.draft(request)));
    }
    @GetMapping("/public/map-shares/{token}")
    public TravelPublicMapShareResponse map(@PathVariable String token) { return TravelMapLinkMapper.map(links.map(token)); }
    @GetMapping("/public/map-shares/{token}/photo-clusters/{clusterId}")
    public TravelMyMapPhotoClusterPageResponse cluster(@PathVariable String token, @PathVariable Long clusterId,
            @RequestParam(name = "page", defaultValue = "0") Integer page, @RequestParam(name = "size", defaultValue = "12") Integer size,
            @RequestParam(name = "focusMediaId", required = false) Long focus) {
        return TravelMapLinkMapper.cluster(links.cluster(token, clusterId, page, size, focus));
    }
    @GetMapping("/public/map-shares/{token}/media/{mediaId}/content")
    public ResponseEntity<?> media(@PathVariable String token, @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail, @RequestParam(name = "w", required = false) Integer width) {
        return media.render(links.media(token, mediaId), thumbnail, width);
    }
}

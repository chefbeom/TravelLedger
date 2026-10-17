package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelExhibitMapper;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel")
public class TravelExhibitController {
    private final ReadTravelExhibitsUseCase exhibits;
    private final TravelMediaHttpResponses responses;
    @GetMapping("/shared-exhibits")
    public TravelSharedExhibitPageResponse received(@AuthenticationPrincipal AppUserPrincipal viewer,
            @RequestParam(name = "page", required = false) Integer page, @RequestParam(name = "size", required = false) Integer size) {
        return TravelExhibitMapper.page(exhibits.received(viewer.userId(), page, size));
    }
    @GetMapping("/shared-exhibits/{shareId}")
    public TravelSharedExhibitDetailResponse detail(@AuthenticationPrincipal AppUserPrincipal viewer, @PathVariable Long shareId) {
        return TravelExhibitMapper.received(exhibits.receivedDetail(viewer.userId(), shareId));
    }
    @GetMapping("/shared-exhibits/{shareId}/media/{mediaId}/content")
    public ResponseEntity<?> media(@AuthenticationPrincipal AppUserPrincipal viewer, @PathVariable Long shareId, @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail, @RequestParam(name = "w", required = false) Integer width) {
        return responses.render(exhibits.receivedMedia(viewer.userId(), shareId, mediaId), thumbnail, width);
    }
    @GetMapping("/public/media/{mediaId}/content")
    public ResponseEntity<?> publicMedia(@PathVariable Long mediaId, @RequestParam("token") String token,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail, @RequestParam(name = "w", required = false) Integer width) {
        return responses.render(exhibits.publicMedia(mediaId, token), thumbnail, width);
    }
}

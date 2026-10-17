package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelAtlasMapper;
import com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase;
import com.playdata.calen.travel.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelAtlasController {
    private final ReadTravelAtlasUseCase reads;
    @GetMapping("/public-trips")
    public TravelPublicTripsOverviewResponse overview(@AuthenticationPrincipal AppUserPrincipal viewer) { return TravelAtlasMapper.overview(reads.overview(viewer.userId())); }
    @GetMapping("/public-trips/photo-clusters/{clusterId}")
    public TravelMyMapPhotoClusterPageResponse cluster(@AuthenticationPrincipal AppUserPrincipal viewer, @PathVariable Long clusterId,
            @RequestParam(name = "page", defaultValue = "0") Integer page, @RequestParam(name = "size", defaultValue = "12") Integer size,
            @RequestParam(name = "focusMediaId", required = false) Long focus) {
        return TravelAtlasMapper.cluster(reads.cluster(viewer.userId(), clusterId, page, size, focus));
    }
    @GetMapping("/community-feed")
    public TravelCommunityFeedPageResponse community(@AuthenticationPrincipal AppUserPrincipal viewer,
            @RequestParam(name = "page", required = false) Integer page, @RequestParam(name = "size", required = false) Integer size) {
        return TravelAtlasMapper.community(reads.community(viewer.userId(), page, size));
    }
}

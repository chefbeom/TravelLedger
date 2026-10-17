package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase;
import com.playdata.calen.travel.dto.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
public class OwnedTravelMapController {
    private final ReadOwnedTravelMapUseCase reads;
    private final TaskExecutor mapReadExecutor;
    public OwnedTravelMapController(ReadOwnedTravelMapUseCase reads, @Qualifier("travelMapReadExecutor") TaskExecutor mapReadExecutor) {
        this.reads = reads; this.mapReadExecutor = mapReadExecutor;
    }
    @GetMapping("/my-map")
    public CompletableFuture<TravelMyMapOverviewResponse> overview(@AuthenticationPrincipal AppUserPrincipal owner) {
        return CompletableFuture.supplyAsync(() -> TravelOwnedMapMapper.response(reads.overview(owner.userId())), mapReadExecutor);
    }
    @GetMapping("/my-map/markers/{markerId}")
    public TravelMyMapMarkerDetailBundleResponse marker(@AuthenticationPrincipal AppUserPrincipal owner, @PathVariable Long markerId) {
        return TravelOwnedMapMapper.response(reads.markerBundle(owner.userId(), markerId));
    }
    @GetMapping("/photo-frame-media")
    public List<TravelPhotoFrameMediaResponse> frames(@AuthenticationPrincipal AppUserPrincipal owner) {
        return reads.frames(owner.userId()).stream().map(TravelOwnedMapMapper::frame).toList();
    }
}

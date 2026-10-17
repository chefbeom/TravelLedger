package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel/my-map/photo-clusters")
public class TravelPhotoClusterController {
    private final ManageTravelPhotoClustersUseCase clusters;
    private final TaskExecutor executor;
    public TravelPhotoClusterController(ManageTravelPhotoClustersUseCase clusters,
            @Qualifier("travelMapReadExecutor") TaskExecutor executor) {
        this.clusters = clusters;
        this.executor = executor;
    }

    @GetMapping("/{clusterId}")
    public CompletableFuture<TravelMyMapPhotoClusterPageResponse> get(
            @AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long clusterId,
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "12") Integer size,
            @RequestParam(name = "focusMediaId", required = false) Long focusMediaId) {
        return CompletableFuture.supplyAsync(() -> TravelClusterDetailMapper.response(
                clusters.page(currentUser.userId(), clusterId, page, size, focusMediaId)), executor);
    }

    @PutMapping("/{clusterId}/representative")
    public TravelMyMapPhotoClusterPageResponse changeRepresentative(
            @AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long clusterId,
            @Valid @RequestBody TravelMyMapClusterRepresentativeRequest request) {
        return TravelClusterDetailMapper.response(clusters.changeRepresentative(currentUser.userId(), clusterId, request.mediaId()));
    }
}

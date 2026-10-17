package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.*;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel/routes/{routeId}/gpx-files")
public class TravelRouteFilesController {
    private final ManageTravelRouteFilesUseCase files;
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public TravelRouteSegmentResponse upload(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId,
            @RequestParam("files") List<MultipartFile> uploads) {
        return TravelRouteMapper.response(files.upload(user.userId(),routeId,TravelRouteUploadMapper.uploads(uploads)));
    }
    @PostMapping("/presign")
    public TravelRouteGpxUploadPrepareResponse prepare(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId,
            @Valid @RequestBody TravelRouteGpxUploadPrepareRequest request) {
        return TravelRouteUploadMapper.response(files.prepare(user.userId(),routeId,TravelRouteUploadMapper.candidates(request)));
    }
    @PostMapping("/complete")
    public TravelRouteSegmentResponse complete(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId,
            @Valid @RequestBody TravelRouteGpxUploadCompleteRequest request) {
        return TravelRouteMapper.response(files.complete(user.userId(),routeId,TravelRouteUploadMapper.completed(request)));
    }
    @PostMapping("/abort")
    public void abort(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId,
            @Valid @RequestBody TravelRouteGpxUploadAbortRequest request) {
        files.abort(user.userId(),routeId,request.objectKeys());
    }
}

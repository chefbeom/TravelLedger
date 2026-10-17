package com.playdata.calen.travel.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.TravelReverseGeocodeResponse;
import com.playdata.calen.travel.adapter.in.web.TravelMediaHttpResponses;
import com.playdata.calen.travel.service.TravelService;
import com.playdata.calen.travel.service.TravelLoginMapPreviewService;
import com.playdata.calen.travel.service.TravelReverseGeocodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelLoginMapPreviewService travelLoginMapPreviewService;
    private final TravelMediaHttpResponses mediaResponses;
    private final TravelReverseGeocodeService travelReverseGeocodeService;
    private final RecordSharingService recordSharingService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.playdata.calen.travel.service.TravelReverseGeocodeDispatcher travelReverseGeocodeDispatcher;



    @GetMapping("/geocode/reverse")
    public java.util.concurrent.CompletableFuture<TravelReverseGeocodeResponse> reverseGeocode(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("lat") double latitude,
            @RequestParam("lon") double longitude
    ) {
        return travelReverseGeocodeDispatcher.reverseGeocode(latitude, longitude);
    }



    @GetMapping("/public/login-map-preview")
    public ResponseEntity<TravelLoginMapPreviewResponse> getLoginMapPreview() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(travelLoginMapPreviewService.getPublicPreview());
    }

    @GetMapping("/public/login-map-preview/markers/{markerNumber}/thumbnail")
    public ResponseEntity<?> getLoginMapPreviewMarkerThumbnail(@PathVariable int markerNumber) {
        return mediaResponses.thumbnailOnly(travelLoginMapPreviewService.getPublicMarkerThumbnail(markerNumber), 320);
    }










    @GetMapping("/record-shares/{shareId}/media/{mediaId}/content")
    public ResponseEntity<?> downloadRecordSharedMedia(@AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long shareId, @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width) {
        var download = recordSharingService.getTravelMedia(currentUser.userId(), shareId, mediaId);
        var response = buildMediaResponse(download, thumbnail, width);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.setCacheControl("private, no-store");
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
    }

    private ResponseEntity<?> buildMediaResponse(TravelService.MediaDownload download, boolean thumbnail, Integer width) {
        return mediaResponses.render(new com.playdata.calen.travel.domain.model.TravelMediaData.Download(
                download.storagePath(), download.contentType(), download.fileName()), thumbnail, width);
    }

}

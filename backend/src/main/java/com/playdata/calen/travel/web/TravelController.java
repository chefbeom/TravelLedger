package com.playdata.calen.travel.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.dto.TravelCategoryCatalogResponse;
import com.playdata.calen.travel.dto.TravelCommunityFeedPageResponse;
import com.playdata.calen.travel.dto.TravelCommunityPostResponse;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.dto.TravelExpenseRecordRequest;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailBundleResponse;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import com.playdata.calen.travel.dto.TravelMemoryRecordRequest;
import com.playdata.calen.travel.dto.TravelMemoryRecordResponse;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanShareRequest;
import com.playdata.calen.travel.dto.TravelPlanShareResponse;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.dto.TravelPortfolioResponse;
import com.playdata.calen.travel.dto.TravelReverseGeocodeResponse;
import com.playdata.calen.travel.dto.TravelRouteSegmentRequest;
import com.playdata.calen.travel.dto.TravelRouteSegmentResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitSummaryResponse;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import com.playdata.calen.travel.service.TravelService;
import com.playdata.calen.travel.service.TravelReverseGeocodeService;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;
    private final TravelMediaStorageService travelMediaStorageService;
    private final TravelReverseGeocodeService travelReverseGeocodeService;
    private final ImageThumbnailService imageThumbnailService;

    @GetMapping("/plans")
    public List<TravelPlanSummaryResponse> getPlans(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return travelService.getPlans(currentUser.userId());
    }

    @GetMapping("/plans/{planId}")
    public TravelPlanDetailResponse getPlan(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId
    ) {
        return travelService.getPlan(currentUser.userId(), planId);
    }

    @GetMapping("/portfolio")
    public TravelPortfolioResponse getPortfolio(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return travelService.getPortfolio(currentUser.userId());
    }

    @GetMapping("/my-map")
    public TravelMyMapOverviewResponse getMyMapOverview(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return travelService.getMyMapOverview(currentUser.userId());
    }

    @GetMapping("/my-map/markers/{markerId}")
    public TravelMyMapMarkerDetailBundleResponse getMyMapMarkerDetails(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long markerId
    ) {
        return travelService.getMyMapMarkerDetailBundle(currentUser.userId(), markerId);
    }

    @GetMapping("/geocode/reverse")
    public TravelReverseGeocodeResponse reverseGeocode(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("lat") double latitude,
            @RequestParam("lon") double longitude
    ) {
        return travelReverseGeocodeService.reverseGeocode(latitude, longitude);
    }

    @GetMapping("/categories")
    public TravelCategoryCatalogResponse getCategoryCatalog(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return travelService.getCategoryCatalog(currentUser.userId());
    }

    @GetMapping("/community-feed")
    public TravelCommunityFeedPageResponse getCommunityFeed(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return travelService.getCommunityFeed(currentUser.userId(), page, size);
    }

    @GetMapping("/shared-exhibits")
    public TravelSharedExhibitPageResponse getSharedExhibits(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return travelService.getSharedExhibits(currentUser.userId(), page, size);
    }

    @GetMapping("/shared-exhibits/{shareId}")
    public TravelSharedExhibitDetailResponse getSharedExhibit(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long shareId
    ) {
        return travelService.getSharedExhibit(currentUser.userId(), shareId);
    }

    @PostMapping("/plans")
    public TravelPlanSummaryResponse createPlan(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody TravelPlanRequest request
    ) {
        return travelService.createPlan(currentUser.userId(), request);
    }

    @PutMapping("/plans/{planId}")
    public TravelPlanSummaryResponse updatePlan(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelPlanRequest request
    ) {
        return travelService.updatePlan(currentUser.userId(), planId, request);
    }

    @PostMapping("/plans/{planId}/shares")
    public TravelPlanShareResponse sharePlan(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelPlanShareRequest request
    ) {
        return travelService.shareCompletedPlan(currentUser.userId(), planId, request.loginId());
    }

    @DeleteMapping("/plans/{planId}")
    public void deletePlan(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId
    ) {
        travelService.deletePlan(currentUser.userId(), planId);
    }

    @PostMapping("/plans/{planId}/routes")
    public TravelRouteSegmentResponse createRouteSegment(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelRouteSegmentRequest request
    ) {
        return travelService.createRouteSegment(currentUser.userId(), planId, request);
    }

    @PutMapping("/routes/{routeId}")
    public TravelRouteSegmentResponse updateRouteSegment(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long routeId,
            @Valid @RequestBody TravelRouteSegmentRequest request
    ) {
        return travelService.updateRouteSegment(currentUser.userId(), routeId, request);
    }

    @PostMapping(value = "/routes/{routeId}/gpx-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TravelRouteSegmentResponse uploadRouteGpxFiles(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long routeId,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return travelService.uploadRouteGpxFiles(currentUser.userId(), routeId, files);
    }

    @DeleteMapping("/routes/{routeId}")
    public void deleteRouteSegment(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long routeId
    ) {
        travelService.deleteRouteSegment(currentUser.userId(), routeId);
    }

    @PostMapping("/plans/{planId}/budget-items")
    public TravelBudgetItemResponse createBudgetItem(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelBudgetItemRequest request
    ) {
        return travelService.createBudgetItem(currentUser.userId(), planId, request);
    }

    @PutMapping("/budget-items/{itemId}")
    public TravelBudgetItemResponse updateBudgetItem(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long itemId,
            @Valid @RequestBody TravelBudgetItemRequest request
    ) {
        return travelService.updateBudgetItem(currentUser.userId(), itemId, request);
    }

    @DeleteMapping("/budget-items/{itemId}")
    public void deleteBudgetItem(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long itemId
    ) {
        travelService.deleteBudgetItem(currentUser.userId(), itemId);
    }

    @PostMapping("/plans/{planId}/records")
    public TravelExpenseRecordResponse createExpenseRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelExpenseRecordRequest request
    ) {
        return travelService.createExpenseRecord(currentUser.userId(), planId, request);
    }

    @PutMapping("/records/{recordId}")
    public TravelExpenseRecordResponse updateExpenseRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long recordId,
            @Valid @RequestBody TravelExpenseRecordRequest request
    ) {
        return travelService.updateExpenseRecord(currentUser.userId(), recordId, request);
    }

    @DeleteMapping("/records/{recordId}")
    public void deleteExpenseRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long recordId
    ) {
        travelService.deleteExpenseRecord(currentUser.userId(), recordId);
    }

    @PostMapping("/plans/{planId}/memories")
    public TravelMemoryRecordResponse createMemoryRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long planId,
            @Valid @RequestBody TravelMemoryRecordRequest request
    ) {
        return travelService.createMemoryRecord(currentUser.userId(), planId, request);
    }

    @PutMapping("/memories/{memoryId}")
    public TravelMemoryRecordResponse updateMemoryRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long memoryId,
            @Valid @RequestBody TravelMemoryRecordRequest request
    ) {
        return travelService.updateMemoryRecord(currentUser.userId(), memoryId, request);
    }

    @DeleteMapping("/memories/{memoryId}")
    public void deleteMemoryRecord(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long memoryId
    ) {
        travelService.deleteMemoryRecord(currentUser.userId(), memoryId);
    }

    @PostMapping("/records/{recordId}/media")
    public List<TravelMediaResponse> uploadRecordMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long recordId,
            @RequestParam TravelMediaType mediaType,
            @RequestParam(required = false) String caption,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return travelService.uploadRecordMedia(currentUser.userId(), recordId, mediaType, caption, files);
    }

    @PostMapping("/records/{recordId}/media/presign")
    public TravelMediaUploadPrepareResponse prepareRecordMediaUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long recordId,
            @Valid @RequestBody TravelMediaUploadPrepareRequest request
    ) {
        return travelService.prepareRecordMediaUpload(currentUser.userId(), recordId, request);
    }

    @PostMapping("/records/{recordId}/media/complete")
    public List<TravelMediaResponse> completeRecordMediaUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long recordId,
            @Valid @RequestBody TravelMediaUploadCompleteRequest request
    ) {
        return travelService.completeRecordMediaUpload(currentUser.userId(), recordId, request);
    }

    @PostMapping("/memories/{memoryId}/media")
    public List<TravelMediaResponse> uploadMemoryMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long memoryId,
            @RequestParam(required = false) String caption,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return travelService.uploadMemoryMedia(currentUser.userId(), memoryId, caption, files);
    }

    @PostMapping("/memories/{memoryId}/media/presign")
    public TravelMediaUploadPrepareResponse prepareMemoryMediaUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long memoryId,
            @Valid @RequestBody TravelMediaUploadPrepareRequest request
    ) {
        return travelService.prepareMemoryMediaUpload(currentUser.userId(), memoryId, request);
    }

    @PostMapping("/memories/{memoryId}/media/complete")
    public List<TravelMediaResponse> completeMemoryMediaUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long memoryId,
            @Valid @RequestBody TravelMediaUploadCompleteRequest request
    ) {
        return travelService.completeMemoryMediaUpload(currentUser.userId(), memoryId, request);
    }

    @DeleteMapping("/media/{mediaId}")
    public void deleteMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long mediaId
    ) {
        travelService.deleteMedia(currentUser.userId(), mediaId);
    }

    @GetMapping("/media/{mediaId}/content")
    public ResponseEntity<?> downloadMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width
    ) {
        TravelService.MediaDownload download = travelService.getMediaDownload(currentUser.userId(), mediaId);
        return buildMediaResponse(download, thumbnail, width);
    }

    @GetMapping("/public/media/{mediaId}/content")
    public ResponseEntity<?> downloadSharedMedia(
            @PathVariable Long mediaId,
            @RequestParam("token") String token,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width
    ) {
        TravelService.MediaDownload download = travelService.getSharedMediaDownload(mediaId, token);
        return buildMediaResponse(download, thumbnail, width);
    }

    @GetMapping("/shared-exhibits/{shareId}/media/{mediaId}/content")
    public ResponseEntity<?> downloadSharedExhibitMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long shareId,
            @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width
    ) {
        TravelService.MediaDownload download = travelService.getSharedExhibitMediaDownload(currentUser.userId(), shareId, mediaId);
        return buildMediaResponse(download, thumbnail, width);
    }

    private ResponseEntity<?> buildMediaResponse(TravelService.MediaDownload download, boolean thumbnail, Integer width) {
        String encodedFileName = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        ContentDisposition disposition = "application/pdf".equalsIgnoreCase(download.contentType())
                ? ContentDisposition.attachment().filename(encodedFileName).build()
                : ContentDisposition.inline().filename(encodedFileName).build();

        if (thumbnail) {
            TravelMediaStorageService.PreparedThumbnail preparedThumbnail = travelMediaStorageService.loadPreparedThumbnail(
                    download.storagePath(),
                    download.contentType(),
                    width
            );
            if (preparedThumbnail != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(preparedThumbnail.contentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                        .header("Cache-Control", "public, max-age=86400")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(preparedThumbnail.resource());
            }

            Resource originalResource = travelMediaStorageService.loadAsResource(download.storagePath());
            return imageThumbnailService.createThumbnail(originalResource, download.contentType(), width)
                    .<ResponseEntity<?>>map(preview -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(preview.contentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                            .header("Cache-Control", "public, max-age=3600")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(preview.bytes()))
                    .orElseGet(() -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(download.contentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                            .header("X-Content-Type-Options", "nosniff")
                            .body(originalResource));
        }

        Resource originalResource = travelMediaStorageService.loadAsResource(download.storagePath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(originalResource);
    }

    @GetMapping("/exchange-rates")
    public List<TravelExchangeRateResponse> getExchangeRates(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) String currencies
    ) {
        return travelService.getExchangeRates(currentUser.userId(), currencies);
    }
}

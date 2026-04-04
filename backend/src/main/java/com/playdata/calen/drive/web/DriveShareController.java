package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveService;
import com.playdata.calen.drive.service.DriveShareService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/file/share")
@RequiredArgsConstructor
public class DriveShareController {

    private final DriveShareService driveShareService;

    @GetMapping("/shared/list")
    public List<DriveDtos.SharedFileResponse> getReceivedShares(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveShareService.getReceivedShares(currentUser.userId());
    }

    @GetMapping("/sent/list")
    public List<DriveDtos.SentShareGroupResponse> getSentShares(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveShareService.getSentShares(currentUser.userId());
    }

    @GetMapping("/search-users")
    public List<DriveDtos.ShareInfoResponse> searchRecipients(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("q") String query
    ) {
        return driveShareService.searchRecipients(currentUser.userId(), query);
    }

    @GetMapping("/{fileId}")
    public List<DriveDtos.ShareInfoResponse> getShareInfo(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return driveShareService.getShareInfo(currentUser.userId(), fileId);
    }

    @PostMapping
    public DriveDtos.ActionResponse shareFiles(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.ShareRequest request
    ) {
        return driveShareService.shareFiles(
                currentUser.userId(),
                request != null ? request.fileIds() : null,
                request != null ? request.recipientLoginId() : null
        );
    }

    @PostMapping("/cancel")
    public DriveDtos.ActionResponse cancelShare(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.ShareRequest request
    ) {
        return driveShareService.cancelShare(
                currentUser.userId(),
                request != null ? request.fileIds() : null,
                request != null ? request.recipientLoginId() : null
        );
    }

    @PostMapping("/cancel-all")
    public DriveDtos.ActionResponse cancelAllShares(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.ShareRequest request
    ) {
        return driveShareService.cancelAllShares(
                currentUser.userId(),
                request != null ? request.fileIds() : null
        );
    }

    @PostMapping("/shared/{fileId}/save")
    public DriveDtos.FileItemResponse saveSharedFile(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId,
            @RequestBody(required = false) DriveDtos.MoveRequest request
    ) {
        return driveShareService.saveSharedFileToDrive(
                currentUser.userId(),
                fileId,
                request != null ? request.targetParentId() : null
        );
    }

    @GetMapping("/shared/{fileId}/download")
    public ResponseEntity<byte[]> downloadSharedFile(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return buildDownloadResponse(driveShareService.downloadSharedFile(currentUser.userId(), fileId));
    }

    @GetMapping("/shared/{fileId}/download-link")
    public DriveDtos.DownloadUrlResponse getSharedDownloadLink(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return DriveDtos.DownloadUrlResponse.builder()
                .downloadUrl(driveShareService.getSharedFileDownloadUrl(currentUser.userId(), fileId))
                .build();
    }

    @GetMapping("/shared/{fileId}/thumbnail")
    public ResponseEntity<byte[]> getSharedThumbnail(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return buildThumbnailResponse(driveShareService.loadSharedThumbnail(currentUser.userId(), fileId, 320), ifNoneMatch);
    }

    private ResponseEntity<byte[]> buildDownloadResponse(DriveService.DriveFilePayload payload) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(payload.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .contentLength(payload.contentLength())
                .contentType(resolveMediaType(payload.contentType()))
                .body(payload.bytes());
    }

    private ResponseEntity<byte[]> buildThumbnailResponse(
            DriveService.ThumbnailPayload payload,
            String ifNoneMatch
    ) {
        CacheControl cacheControl = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate().mustRevalidate();
        if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
            return ResponseEntity.noContent().cacheControl(cacheControl).build();
        }

        if (matchesEtag(ifNoneMatch, payload.eTag())) {
            return ResponseEntity.status(304)
                    .cacheControl(cacheControl)
                    .eTag(payload.eTag())
                    .lastModified(payload.lastModifiedEpochMillis())
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .eTag(payload.eTag())
                .lastModified(payload.lastModifiedEpochMillis())
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.bytes());
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean matchesEtag(String ifNoneMatch, String eTag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || eTag == null || eTag.isBlank()) {
            return false;
        }
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim)
                .map(this::normalizeEtag)
                .anyMatch(eTag::equals);
    }

    private String normalizeEtag(String rawEtag) {
        String normalized = rawEtag == null ? "" : rawEtag.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}

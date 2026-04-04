package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class DriveFileController {

    private final DriveService driveService;

    @GetMapping("/list")
    public List<DriveDtos.FileItemResponse> list(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveService.list(currentUser.userId());
    }

    @GetMapping("/list/page")
    public DriveDtos.FileListPageResponse listPage(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @ModelAttribute DriveDtos.ListPageRequest request
    ) {
        return driveService.listPage(currentUser.userId(), request);
    }

    @GetMapping("/home-summary")
    public DriveDtos.HomeSummaryResponse getHomeSummary(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveService.getHomeSummary(currentUser.userId());
    }

    @GetMapping("/recent")
    public List<DriveDtos.FileItemResponse> getRecent(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveService.getRecentFiles(currentUser.userId());
    }

    @GetMapping("/trash")
    public List<DriveDtos.FileItemResponse> getTrash(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveService.getTrashItems(currentUser.userId());
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return buildDownloadResponse(driveService.downloadFile(currentUser.userId(), fileId));
    }

    @GetMapping("/{fileId}/download-link")
    public DriveDtos.DownloadUrlResponse getDownloadLink(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return DriveDtos.DownloadUrlResponse.builder()
                .downloadUrl(driveService.getDownloadUrl(currentUser.userId(), fileId))
                .build();
    }

    @GetMapping("/{fileId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return buildThumbnailResponse(driveService.loadOwnedThumbnail(currentUser.userId(), fileId, 320), ifNoneMatch);
    }

    @PostMapping("/folder")
    public DriveDtos.FileItemResponse createFolder(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.FolderRequest request
    ) {
        return driveService.createFolder(currentUser.userId(), request);
    }

    @PatchMapping("/{fileId}/trash")
    public DriveDtos.ActionResponse moveToTrash(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return driveService.moveToTrash(currentUser.userId(), fileId);
    }

    @PatchMapping("/{fileId}/restore")
    public DriveDtos.ActionResponse restore(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return driveService.restoreFromTrash(currentUser.userId(), fileId);
    }

    @DeleteMapping("/{fileId}")
    public DriveDtos.ActionResponse delete(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId
    ) {
        return driveService.deletePermanently(currentUser.userId(), fileId);
    }

    @DeleteMapping("/trash")
    public DriveDtos.ActionResponse clearTrash(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveService.clearTrash(currentUser.userId());
    }

    @PatchMapping("/{fileId}/move")
    public DriveDtos.ActionResponse moveSingle(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId,
            @RequestBody DriveDtos.MoveRequest request
    ) {
        return driveService.moveToFolder(currentUser.userId(), fileId, request != null ? request.targetParentId() : null);
    }

    @PatchMapping("/{fileId}/rename")
    public DriveDtos.FileItemResponse rename(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long fileId,
            @RequestBody DriveDtos.RenameRequest request
    ) {
        return driveService.renameItem(currentUser.userId(), fileId, request != null ? request.fileName() : null);
    }

    @PatchMapping("/move")
    public DriveDtos.ActionResponse moveBatch(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.MoveBatchRequest request
    ) {
        return driveService.moveItemsToFolder(
                currentUser.userId(),
                request != null ? request.fileIds() : null,
                request != null ? request.targetParentId() : null
        );
    }

    @PatchMapping("/restore")
    public DriveDtos.ActionResponse restoreBatch(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.RestoreBatchRequest request
    ) {
        return driveService.restoreItemsFromTrash(currentUser.userId(), request != null ? request.fileIds() : null);
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
            return ResponseEntity.noContent()
                    .cacheControl(cacheControl)
                    .build();
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

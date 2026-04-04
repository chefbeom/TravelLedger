package com.playdata.calen.familyalbum.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.familyalbum.dto.FamilyAlbumBootstrapResponse;
import com.playdata.calen.familyalbum.dto.FamilyAlbumCreateRequest;
import com.playdata.calen.familyalbum.dto.FamilyMediaPageResponse;
import com.playdata.calen.familyalbum.dto.FamilyAlbumResponse;
import com.playdata.calen.familyalbum.dto.FamilyCategoryCreateRequest;
import com.playdata.calen.familyalbum.dto.FamilyCategoryResponse;
import com.playdata.calen.familyalbum.dto.FamilyMediaResponse;
import com.playdata.calen.familyalbum.dto.FamilyUserSearchResponse;
import com.playdata.calen.familyalbum.service.FamilyAlbumService;
import com.playdata.calen.familyalbum.service.FamilyMediaStorageService;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/family-album")
@RequiredArgsConstructor
public class FamilyAlbumController {

    private final FamilyAlbumService familyAlbumService;
    private final FamilyMediaStorageService familyMediaStorageService;

    @GetMapping("/bootstrap")
    public FamilyAlbumBootstrapResponse getBootstrap(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return familyAlbumService.getBootstrap(currentUser.userId());
    }

    @GetMapping("/categories/{categoryId}/media")
    public FamilyMediaPageResponse getCategoryMediaPage(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long categoryId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return familyAlbumService.getCategoryMediaPage(currentUser.userId(), categoryId, page, size);
    }

    @GetMapping("/albums/{albumId}/media")
    public FamilyMediaPageResponse getAlbumMediaPage(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long albumId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return familyAlbumService.getAlbumMediaPage(currentUser.userId(), albumId, page, size);
    }

    @GetMapping("/users/search")
    public List<FamilyUserSearchResponse> searchUsers(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam String q
    ) {
        return familyAlbumService.searchUsers(currentUser.userId(), q);
    }

    @PostMapping("/categories")
    public FamilyCategoryResponse createCategory(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody FamilyCategoryCreateRequest request
    ) {
        return familyAlbumService.createCategory(currentUser.userId(), request);
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<FamilyMediaResponse> uploadMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam Long categoryId,
            @RequestParam(required = false) String caption,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return familyAlbumService.uploadMedia(currentUser.userId(), categoryId, caption, files);
    }

    @PostMapping("/albums")
    public FamilyAlbumResponse createAlbum(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody FamilyAlbumCreateRequest request
    ) {
        return familyAlbumService.createAlbum(currentUser.userId(), request);
    }

    @GetMapping("/media/{mediaId}/content")
    public ResponseEntity<?> downloadMedia(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width
    ) {
        FamilyAlbumService.MediaDownload download = familyAlbumService.getMediaDownload(currentUser.userId(), mediaId);
        String encodedFileName = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8).replace("+", "%20");

        if (thumbnail) {
            FamilyMediaStorageService.ThumbnailContent thumbnailContent = familyMediaStorageService.loadThumbnail(
                    download.storagePath(),
                    download.contentType(),
                    width
            );
            if (thumbnailContent == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(thumbnailContent.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(encodedFileName).build().toString())
                    .header("Cache-Control", "public, max-age=3600")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(thumbnailContent.resource());
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(encodedFileName).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }
}

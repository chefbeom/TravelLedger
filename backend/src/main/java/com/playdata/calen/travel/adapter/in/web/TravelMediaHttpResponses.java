package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** HTTP representation only. Callers must resolve ownership/share permissions before passing metadata. */
@Component
@RequiredArgsConstructor
public class TravelMediaHttpResponses {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("com.playdata.calen.travel.web.TravelController");
    private final TravelMediaStorageService travelMediaStorageService;

    public ResponseEntity<?> render(Download download, boolean thumbnail, Integer width) {
        ContentDisposition disposition = buildContentDisposition(download);

        if (thumbnail) {
            byte[] thumbnailBytes = loadThumbnailBytes(download, width);
            if (thumbnailBytes != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(resolveThumbnailContentType(download.contentType())))
                        .contentLength(thumbnailBytes.length)
                        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                        .header("Cache-Control", "public, max-age=86400")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(new ByteArrayResource(thumbnailBytes));
            }
            log.warn("Travel thumbnail is unavailable; serving original media. path={}, requestedWidth={}", download.storagePath(), width);
        }

        Resource originalResource = travelMediaStorageService.loadAsResource(download.storagePath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(originalResource);
    }

    public ResponseEntity<?> thumbnailOnly(Download download, Integer width) {
        byte[] thumbnailBytes = loadThumbnailBytes(download, width);
        if (thumbnailBytes == null) {
            return ResponseEntity.notFound()
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(resolveThumbnailContentType(download.contentType())))
                .contentLength(thumbnailBytes.length)
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(thumbnailBytes));
    }

    private byte[] loadThumbnailBytes(Download download, Integer width) {
        try {
            TravelMediaStorageService.PreparedThumbnail preparedThumbnail = travelMediaStorageService.loadThumbnail(
                    download.storagePath(),
                    download.contentType(),
                    width
            );
            if (preparedThumbnail == null) {
                return null;
            }
            try (var inputStream = preparedThumbnail.resource().getInputStream()) {
                return inputStream.readAllBytes();
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to serve thumbnail for media path={}", download.storagePath(), exception);
            return null;
        }
    }

    private String resolveThumbnailContentType(String originalContentType) {
        String normalized = String.valueOf(originalContentType).trim().toLowerCase();
        return "image/png".equals(normalized) || "image/gif".equals(normalized) || "image/webp".equals(normalized)
                ? "image/png"
                : "image/jpeg";
    }

    private ContentDisposition buildContentDisposition(Download download) {
        String fileName = download.fileName() == null || download.fileName().isBlank() ? "media" : download.fileName();
        return "application/pdf".equalsIgnoreCase(download.contentType())
                ? ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build();
    }
}

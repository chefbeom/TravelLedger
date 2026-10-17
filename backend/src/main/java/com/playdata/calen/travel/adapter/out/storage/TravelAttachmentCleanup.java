package com.playdata.calen.travel.adapter.out.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Adapter-local cleanup for already ownership-checked rows, never exposed through application ports. */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class TravelAttachmentCleanup {
    private final TravelDriveLinkService drive;
    private final TravelMediaStorageService storage;
    private final ObjectMapper json;
    private record ImageDeletion(String path, String contentType) {}
    private record StoredGpx(String originalFileName, String storagePath, String contentType, long fileSize) {}

    public void removeMediaFilesAndLinks(List<TravelMediaAsset> assets) {
        drive.removeMediaLinks(assets);
        var files = assets.stream().map(a -> new ImageDeletion(a.getStoragePath(), a.getContentType())).toList();
        afterSuccessfulCompletion(() -> files.forEach(file -> safely(() -> storage.deleteImageWithThumbnailsQuietly(file.path(), file.contentType()))));
    }

    public void removeRouteFilesAndLinks(List<TravelRouteSegment> routes) {
        routes.forEach(drive::removeRouteGpxLinks);
        var files = routes.stream().flatMap(route -> gpxFiles(route.getGpxFilesJson()).stream()).toList();
        afterSuccessfulCompletion(() -> files.forEach(file -> safely(() -> storage.deleteQuietly(file))));
    }

    private List<String> gpxFiles(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<StoredGpx> files = json.readValue(raw, new TypeReference<>() {});
            return files == null ? List.of() : files.stream().filter(f -> f != null && f.storagePath() != null && !f.storagePath().isBlank())
                    .map(StoredGpx::storagePath).toList();
        } catch (JsonProcessingException ignored) { return List.of(); }
    }

    private void afterSuccessfulCompletion(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) action.run();
            }
        });
    }

    private void safely(Runnable deletion) {
        try { deletion.run(); }
        catch (RuntimeException failure) {
            log.warn("Travel attachment cleanup failed after commit ({})", failure.getClass().getSimpleName());
        }
    }
}

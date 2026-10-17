package com.playdata.calen.travel.adapter.out.storage;

import com.playdata.calen.travel.application.port.out.TravelMediaStoragePort;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TravelMediaStorageAdapter implements TravelMediaStoragePort {
    private final TravelMediaStorageService storage;
    @Override
    public boolean supportsPresigned() {
        return storage.supportsPresignedUpload();
    }

    @Override
    public List<Target> prepare(Long owner, Long plan, Long record, List<Candidate> files) {
        var candidates = files.stream().map(file -> new TravelMediaStorageService.UploadCandidate(
                file.originalFileName(), file.contentType(), file.fileSize(),
                (file.thumbnails() == null ? List.<ThumbnailCandidate>of() : file.thumbnails()).stream()
                        .map(thumbnail -> new TravelMediaStorageService.PreparedThumbnailUploadCandidate(
                                thumbnail.variant(), thumbnail.contentType(), thumbnail.fileSize()))
                        .toList())).toList();
        return storage.preparePresignedUploads(owner, plan, record, candidates).stream()
                .map(file -> new Target(file.method(), file.uploadUrl(), file.objectKey(), file.storedFileName(),
                        file.originalFileName(), file.contentType(), file.fileSize(),
                        (file.thumbnails() == null ? List.<TravelMediaStorageService.PresignedThumbnailUpload>of() : file.thumbnails()).stream()
                                .map(thumbnail -> new ThumbnailTarget(thumbnail.variant(), thumbnail.method(), thumbnail.uploadUrl(),
                                        thumbnail.objectKey(), thumbnail.contentType(), thumbnail.fileSize()))
                                .toList()))
                .toList();
    }

    @Override
    public Stored complete(Long owner, Long plan, Long record, Completed file) {
        var thumbnails = (file.thumbnails() == null ? List.<CompletedThumbnail>of() : file.thumbnails()).stream()
                .map(thumbnail -> new TravelMediaStorageService.CompletedPreparedThumbnailUpload(
                        thumbnail.variant(), thumbnail.objectKey(), thumbnail.contentType(), thumbnail.fileSize()))
                .toList();
        var result = storage.completePresignedUpload(owner, plan, record, new TravelMediaStorageService.CompletedUpload(
                file.objectKey(), file.originalFileName(), file.contentType(), file.fileSize(), thumbnails));
        return new Stored(result.originalFileName(), result.storedFileName(), result.storagePath(), result.contentType(), result.fileSize());
    }

    @Override
    public void delete(String path, String contentType) {
        storage.deleteImageWithThumbnailsQuietly(path, contentType);
    }
}

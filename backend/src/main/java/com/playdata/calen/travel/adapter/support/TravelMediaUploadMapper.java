package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.dto.*;
import java.util.List;

public final class TravelMediaUploadMapper {
    private TravelMediaUploadMapper() {}
    public static List<Candidate> candidates(TravelMediaUploadPrepareRequest request) {
        if (request.files() == null) {
            return null;
        }
        return request.files().stream().map(file -> file == null ? null : new Candidate(
                file.originalFileName(), file.contentType(), file.fileSize(),
                file.thumbnails() == null ? null : file.thumbnails().stream()
                        .map(thumbnail -> thumbnail == null ? null : new ThumbnailCandidate(
                                thumbnail.variant(), thumbnail.contentType(), thumbnail.fileSize()))
                        .toList())).toList();
    }

    public static List<Completed> completed(TravelMediaUploadCompleteRequest request) {
        if (request.files() == null) {
            return null;
        }
        return request.files().stream().map(file -> file == null ? null : new Completed(
                file.objectKey(), file.originalFileName(), file.contentType(), file.fileSize(),
                file.thumbnails() == null ? null : file.thumbnails().stream()
                        .map(thumbnail -> thumbnail == null ? null : new CompletedThumbnail(
                                thumbnail.variant(), thumbnail.objectKey(), thumbnail.contentType(), thumbnail.fileSize()))
                        .toList(),
                file.gpsLatitude(), file.gpsLongitude())).toList();
    }

    public static TravelMediaUploadPrepareResponse response(Prepared prepared) {
        return new TravelMediaUploadPrepareResponse(prepared.uploadMode(), prepared.uploads().stream()
                .map(file -> new TravelMediaUploadTargetResponse(file.method(), file.uploadUrl(), file.objectKey(), file.storedFileName(),
                        file.originalFileName(), file.contentType(), file.fileSize(),
                        file.thumbnails().stream().map(thumbnail -> new TravelPreparedThumbnailUploadTargetResponse(
                                thumbnail.variant(), thumbnail.method(), thumbnail.uploadUrl(), thumbnail.objectKey(),
                                thumbnail.contentType(), thumbnail.fileSize())).toList()))
                .toList());
    }

    public static List<TravelMediaResponse> responses(List<Media> values) {
        return values.stream().map(TravelClusterDetailMapper::owned).toList();
    }
}

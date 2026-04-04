package com.playdata.calen.travel.service;

import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterDetailResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterSummaryResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoPinResponse;
import java.util.List;
import java.util.Optional;

public record TravelMyMapPhotoClusterSnapshot(
        int photoMarkerCount,
        int photoClusterCount,
        List<TravelMyMapPhotoClusterSummaryResponse> summaries,
        List<TravelMyMapPhotoClusterDetailResponse> details,
        List<TravelMyMapPhotoPinResponse> pins
) {

    public Optional<TravelMyMapPhotoClusterDetailResponse> findDetail(Long clusterId) {
        return (details == null ? List.<TravelMyMapPhotoClusterDetailResponse>of() : details).stream()
                .filter(detail -> detail != null && detail.id() != null && detail.id().equals(clusterId))
                .findFirst();
    }

    public Optional<TravelMyMapPhotoClusterDetailResponse> findDetailContainingMedia(Long mediaId) {
        return (details == null ? List.<TravelMyMapPhotoClusterDetailResponse>of() : details).stream()
                .filter(detail -> detail != null)
                .filter(detail -> (detail.photos() == null ? List.<TravelMediaResponse>of() : detail.photos()).stream()
                        .anyMatch(photo -> photo != null && photo.id() != null && photo.id().equals(mediaId)))
                .findFirst();
    }
}

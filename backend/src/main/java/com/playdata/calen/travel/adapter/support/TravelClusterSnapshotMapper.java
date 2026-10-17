package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterSummaryResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoPinResponse;
import com.playdata.calen.travel.service.TravelMyMapPhotoClusterSnapshot;
import java.util.List;

public final class TravelClusterSnapshotMapper {
    private TravelClusterSnapshotMapper() {}
    public static TravelMyMapPhotoClusterSnapshot response(Snapshot snapshot) {
        if (snapshot == null) return null;
        return new TravelMyMapPhotoClusterSnapshot(snapshot.photoMarkerCount(), snapshot.photoClusterCount(),
                snapshot.summaries().stream().map(TravelClusterSnapshotMapper::summary).toList(), List.of(),
                snapshot.pins().stream().map(TravelClusterSnapshotMapper::pin).toList());
    }
    private static TravelMyMapPhotoClusterSummaryResponse summary(Summary s) {
        return new TravelMyMapPhotoClusterSummaryResponse(s.id(), s.representativeMediaId(), s.representativeRecordId(), s.planId(), s.planName(),
                s.planColorHex(), s.memoryDate(), s.memoryTime(), s.category(), s.title(), s.country(), s.region(), s.placeName(), s.latitude(), s.longitude(),
                s.photoCount(), s.memoryCount(), s.maxDistanceMeters(), s.representativeOverride(), contentUrl(s.representativeMediaId()));
    }
    private static TravelMyMapPhotoPinResponse pin(Pin p) {
        return new TravelMyMapPhotoPinResponse(p.mediaId(), p.clusterId(), p.recordId(), p.planId(), p.planName(), p.planColorHex(), p.memoryDate(), p.memoryTime(),
                p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), contentUrl(p.mediaId()), p.representative(), p.representativeOverride());
    }
    private static String contentUrl(Long mediaId) { return "/api/travel/media/" + mediaId + "/content"; }
}

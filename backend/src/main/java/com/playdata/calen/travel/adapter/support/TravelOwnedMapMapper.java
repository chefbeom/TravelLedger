package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.service.TravelMyMapPhotoClusterSnapshot;
import java.util.List;
import java.util.function.Function;

/** Keeps the existing owned HTTP/cache representation, including opaque cached URL strings and nulls. */
public final class TravelOwnedMapMapper {
    private TravelOwnedMapMapper() {}
    public static TravelMyMapOverviewResponse response(Overview value) {
        if (value == null) return null;
        return new TravelMyMapOverviewResponse(value.planCount(), value.markerCount(), value.photoMarkerCount(), value.photoClusterCount(), value.routeCount(),
                value.totalDistanceKm(), map(value.markers(), TravelOwnedMapMapper::marker), map(value.clusters(), TravelOwnedMapMapper::cluster),
                map(value.pins(), TravelOwnedMapMapper::pin), map(value.routes(), TravelOwnedMapMapper::route));
    }
    public static Overview value(TravelMyMapOverviewResponse value) {
        if (value == null) return null;
        return new Overview(value.includedPlanCount(), value.markerCount(), value.photoMarkerCount(), value.photoClusterCount(), value.routeCount(), value.totalDistanceKm(),
                map(value.markers(), marker -> new MarkerSummary(new Marker(marker.id(), marker.planId(), marker.planName(), marker.planColorHex(), marker.memoryDate(), marker.memoryTime(),
                        marker.category(), marker.title(), marker.country(), marker.region(), marker.placeName(), marker.latitude(), marker.longitude()), marker.photoUrl())),
                map(value.photoClusters(), TravelOwnedMapMapper::cluster), map(value.photoPins(), TravelOwnedMapMapper::pin), map(value.routes(), TravelOwnedMapMapper::route));
    }
    public static Photos photos(TravelMyMapPhotoClusterSnapshot snapshot) {
        return new Photos(snapshot.photoMarkerCount(), snapshot.photoClusterCount(), map(snapshot.summaries(), TravelOwnedMapMapper::cluster), map(snapshot.pins(), TravelOwnedMapMapper::pin));
    }
    public static TravelMyMapMarkerDetailBundleResponse response(Bundle value) {
        return new TravelMyMapMarkerDetailBundleResponse(value.selectedMarkerId(), map(value.markers(), detail -> {
            var marker = detail.marker();
            return new TravelMyMapMarkerDetailResponse(marker.id(), marker.planId(), marker.planName(), marker.colorHex(), marker.date(), marker.time(), marker.category(), marker.title(),
                    marker.country(), marker.region(), marker.placeName(), marker.latitude(), marker.longitude(), detail.shared(), detail.memo(), detail.photos().size(),
                    map(detail.photos(), TravelClusterDetailMapper::owned));
        }));
    }
    public static TravelPhotoFrameMediaResponse frame(Frame value) {
        return new TravelPhotoFrameMediaResponse(value.id(), value.planId(), value.planName(), value.recordType(), value.originalFileName(), value.caption(), value.uploadedAt(),
                value.contentUrl(), value.date(), value.time(), value.title(), value.country(), value.region(), value.placeName(), value.latitude(), value.longitude(), value.gpsLatitude(), value.gpsLongitude());
    }
    private static TravelMyMapMarkerSummaryResponse marker(MarkerSummary value) {
        var marker = value.marker();
        return new TravelMyMapMarkerSummaryResponse(marker.id(), marker.planId(), marker.planName(), marker.colorHex(), marker.date(), marker.time(), marker.category(), marker.title(),
                marker.country(), marker.region(), marker.placeName(), value.photoUrl(), marker.latitude(), marker.longitude());
    }
    private static Cluster cluster(TravelMyMapPhotoClusterSummaryResponse value) {
        return new Cluster(new TravelPhotoClusterData.Summary(value.id(), value.representativeMediaId(), value.representativeRecordId(), value.planId(), value.planName(), value.planColorHex(),
                value.memoryDate(), value.memoryTime(), value.category(), value.title(), value.country(), value.region(), value.placeName(), value.latitude(), value.longitude(), value.photoCount(),
                value.memoryCount(), value.maxDistanceMeters(), value.representativeOverride()), value.representativePhotoUrl());
    }
    private static TravelMyMapPhotoClusterSummaryResponse cluster(Cluster value) {
        var row = value.value();
        return new TravelMyMapPhotoClusterSummaryResponse(row.id(), row.representativeMediaId(), row.representativeRecordId(), row.planId(), row.planName(), row.planColorHex(), row.memoryDate(),
                row.memoryTime(), row.category(), row.title(), row.country(), row.region(), row.placeName(), row.latitude(), row.longitude(), row.photoCount(), row.memoryCount(), row.maxDistanceMeters(),
                row.representativeOverride(), value.photoUrl());
    }
    private static Pin pin(TravelMyMapPhotoPinResponse value) {
        return new Pin(new TravelPhotoClusterData.Pin(value.mediaId(), value.clusterId(), value.recordId(), value.planId(), value.planName(), value.planColorHex(), value.memoryDate(), value.memoryTime(),
                value.category(), value.title(), value.country(), value.region(), value.placeName(), value.latitude(), value.longitude(), value.representative(), value.representativeOverride()), value.photoUrl());
    }
    private static TravelMyMapPhotoPinResponse pin(Pin value) {
        var row = value.value();
        return new TravelMyMapPhotoPinResponse(row.mediaId(), row.clusterId(), row.recordId(), row.planId(), row.planName(), row.planColorHex(), row.memoryDate(), row.memoryTime(), row.category(),
                row.title(), row.country(), row.region(), row.placeName(), row.latitude(), row.longitude(), value.photoUrl(), row.representative(), row.representativeOverride());
    }
    private static TravelRouteData.Result route(TravelRouteSegmentResponse value) {
        return new TravelRouteData.Result(value.id(), new TravelRouteData.Plan(value.planId(), value.planName(), value.planColorHex()), value.routeDate(), value.title(), value.transportMode(),
                value.distanceKm(), value.durationMinutes(), value.stepCount(), value.sourceType(), value.startPlaceName(), value.endPlaceName(), value.lineColorHex(), value.lineStyle(), value.gpxFileNames(),
                value.memo(), map(value.points(), point -> new TravelRouteData.Point(point.latitude(), point.longitude(), point.pointType(), point.linkedMemoryId(), point.label())));
    }
    private static TravelRouteSegmentResponse route(TravelRouteData.Result value) {
        var plan = value.plan();
        return new TravelRouteSegmentResponse(value.id(), plan.id(), plan.name(), plan.colorHex(), value.routeDate(), value.title(), value.transportMode(), value.distanceKm(), value.durationMinutes(),
                value.stepCount(), value.sourceType(), value.startPlaceName(), value.endPlaceName(), value.lineColorHex(), value.lineStyle(), value.gpxFileNames(), value.memo(),
                map(value.points(), point -> new TravelRoutePointResponse(point.latitude(), point.longitude(), point.pointType(), point.linkedMemoryId(), point.label())));
    }
    private static <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
        return values == null ? null : values.stream().map(value -> value == null ? null : mapper.apply(value)).toList();
    }
}

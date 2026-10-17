package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Detached query values. URL strings are opaque presentation references, including previously cached ones. */
public final class TravelOwnedMapData {
    private TravelOwnedMapData() {}
    public record Bounds(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude,
            boolean allLongitudes, boolean crossesDateLine) {}
    public record Location(Long id, BigDecimal latitude, BigDecimal longitude) {}
    public record Marker(Long id, Long planId, String planName, String colorHex, LocalDate date, LocalTime time,
            String category, String title, String country, String region, String placeName, BigDecimal latitude, BigDecimal longitude) {}
    public record MarkerSummary(Marker marker, String photoUrl) {}
    public record Cluster(TravelPhotoClusterData.Summary value, String photoUrl) {}
    public record Pin(TravelPhotoClusterData.Pin value, String photoUrl) {}
    public record Photos(int markerCount, int clusterCount, List<Cluster> clusters, List<Pin> pins) {}
    public record Route(Long id, TravelRouteData.Plan plan, TravelRouteData.Value value, String gpxFiles) {}
    public record Overview(int planCount, int markerCount, int photoMarkerCount, int photoClusterCount, int routeCount,
            BigDecimal totalDistanceKm, List<MarkerSummary> markers, List<Cluster> clusters, List<Pin> pins, List<TravelRouteData.Result> routes) {}
    public record MarkerDetail(Marker marker, Boolean shared, String memo, List<TravelClusterDetailData.Media> photos) {}
    public record Bundle(Long selectedMarkerId, List<MarkerDetail> markers) {}
    public record Frame(Long id, Long planId, String planName, TravelRecordType recordType, String originalFileName,
            String caption, LocalDateTime uploadedAt, String contentUrl, LocalDate date, LocalTime time, String title,
            String country, String region, String placeName, BigDecimal latitude, BigDecimal longitude, BigDecimal gpsLatitude, BigDecimal gpsLongitude) {}
}

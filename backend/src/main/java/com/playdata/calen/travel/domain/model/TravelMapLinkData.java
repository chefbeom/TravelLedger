package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** A bearer link has its own selected scope; it is not a public-plan/community publication. */
public final class TravelMapLinkData {
    private TravelMapLinkData() {}
    public record Scope(List<Long> plans, Set<Long> records, Set<Long> media, Set<Long> routes) {}
    public record Draft(String title, List<Long> plans, List<Long> records, List<Long> media, List<Long> routes) {}
    public record Link(Long owner, String ownerLogin, String ownerDisplay, String token, String title,
            Scope scope, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record MarkerPhoto(TravelOwnedMapData.Marker marker, Long photo) {}
    public record Overview(int plans, int photoCount, BigDecimal distance, List<MarkerPhoto> markers,
            List<TravelPhotoClusterData.Summary> clusters, List<TravelPhotoClusterData.Pin> pins, List<TravelRouteData.Result> routes) {}
    public record PublicMap(Link link, Overview overview) {}
    public record Cluster(String token, TravelClusterDetailData.Page page) {}
    public record Asset(Long id, Long planId, Long recordId, TravelRecordType recordType, TravelMediaType mediaType,
            boolean recordCoordinates, boolean clusterCoordinates, TravelMediaData.Download download) {}
    public record PreviewMarker(int number, BigDecimal latitude, BigDecimal longitude, boolean photo) {}
    public record PreviewPoint(BigDecimal latitude, BigDecimal longitude) {}
    public record PreviewRoute(int number, List<PreviewPoint> points) {}
    public record Preview(boolean enabled, List<PreviewMarker> markers, List<PreviewRoute> routes) {
        public static Preview empty() { return new Preview(false, List.of(), List.of()); }
    }
}

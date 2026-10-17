package com.playdata.calen.travel.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detached values shared by cluster calculation and its stored map projection. */
public final class TravelPhotoClusterData {
    private TravelPhotoClusterData() {}

    public record PhotoPoint(Long mediaId, Long recordId, Long planId, String planName, String planColorHex,
            LocalDate memoryDate, LocalTime memoryTime, String category, String title, String country, String region,
            String placeName, BigDecimal latitude, BigDecimal longitude, LocalDateTime uploadedAt, boolean representativeOverride) {}

    public record PhotoCluster(Long id, PhotoPoint representative, List<PhotoPoint> members, BigDecimal centroidLatitude,
            BigDecimal centroidLongitude, BigDecimal maxDistanceMeters, int memoryCount) {
        public int photoCount() { return members == null ? 0 : members.size(); }
        public Set<Long> memberMediaIds() {
            Set<Long> ids = new HashSet<>();
            if (members != null) members.forEach(member -> ids.add(member.mediaId()));
            return ids;
        }
    }

    public record Summary(Long id, Long representativeMediaId, Long representativeRecordId, Long planId, String planName,
            String planColorHex, LocalDate memoryDate, LocalTime memoryTime, String category, String title, String country,
            String region, String placeName, BigDecimal latitude, BigDecimal longitude, int photoCount, int memoryCount,
            BigDecimal maxDistanceMeters, boolean representativeOverride) {}

    public record Pin(Long mediaId, Long clusterId, Long recordId, Long planId, String planName, String planColorHex,
            LocalDate memoryDate, LocalTime memoryTime, String category, String title, String country, String region,
            String placeName, BigDecimal latitude, BigDecimal longitude, boolean representative, boolean representativeOverride) {}

    public record Snapshot(int photoMarkerCount, int photoClusterCount, List<Summary> summaries, List<Pin> pins) {
        public static Snapshot empty() { return new Snapshot(0, 0, List.of(), List.of()); }
    }
}

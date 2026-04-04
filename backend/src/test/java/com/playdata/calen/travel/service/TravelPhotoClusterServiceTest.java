package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelPhotoClusterServiceTest {

    private final TravelPhotoClusterService clusterService = new TravelPhotoClusterService();

    @Test
    void keepsPhotosAtSameCoordinateInSingleCluster() {
        List<TravelPhotoClusterService.PhotoPoint> points = List.of(
                point(1L, 37.5665000d, 126.9780000d, false, LocalDateTime.of(2026, 4, 4, 10, 0)),
                point(2L, 37.5665000d, 126.9780000d, false, LocalDateTime.of(2026, 4, 4, 11, 0))
        );

        List<TravelPhotoClusterService.PhotoCluster> clusters = clusterService.cluster(points);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).photoCount()).isEqualTo(2);
    }

    @Test
    void splitsLongChainClusterWhenCentroidDistanceExceedsTenMeters() {
        double baseLatitude = 37.5665000d;
        double baseLongitude = 126.9780000d;

        List<TravelPhotoClusterService.PhotoPoint> points = List.of(
                point(1L, baseLatitude, baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 0)),
                point(2L, baseLatitude + toLatitudeDegrees(2.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 1)),
                point(3L, baseLatitude + toLatitudeDegrees(4.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 2)),
                point(4L, baseLatitude + toLatitudeDegrees(6.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 3)),
                point(5L, baseLatitude + toLatitudeDegrees(8.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 4)),
                point(6L, baseLatitude + toLatitudeDegrees(10.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 5)),
                point(7L, baseLatitude + toLatitudeDegrees(12.0d), baseLongitude, false, LocalDateTime.of(2026, 4, 4, 10, 6))
        );

        List<TravelPhotoClusterService.PhotoCluster> clusters = clusterService.cluster(points);

        assertThat(clusters).hasSizeGreaterThan(1);
        assertThat(clusters).allSatisfy(cluster ->
                assertThat(cluster.maxDistanceMeters().doubleValue()).isLessThanOrEqualTo(10.01d)
        );
    }

    @Test
    void prefersRepresentativeOverrideOverCentroidSelection() {
        List<TravelPhotoClusterService.PhotoPoint> points = List.of(
                point(1L, 37.5665000d, 126.9780000d, false, LocalDateTime.of(2026, 4, 4, 10, 0)),
                point(2L, 37.5665005d, 126.9780004d, true, LocalDateTime.of(2026, 4, 4, 11, 0)),
                point(3L, 37.5665003d, 126.9780002d, false, LocalDateTime.of(2026, 4, 4, 12, 0))
        );

        List<TravelPhotoClusterService.PhotoCluster> clusters = clusterService.cluster(points);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).representative().mediaId()).isEqualTo(2L);
    }

    @Test
    void usesFirstOrderedPhotoAsRepresentativeWhenNoOverrideExists() {
        List<TravelPhotoClusterService.PhotoPoint> points = List.of(
                point(1L, 37.5665000d, 126.9780000d, false, LocalDateTime.of(2026, 4, 4, 9, 0)),
                point(2L, 37.5665003d, 126.9780002d, false, LocalDateTime.of(2026, 4, 4, 11, 0)),
                point(3L, 37.5665005d, 126.9780004d, false, LocalDateTime.of(2026, 4, 4, 10, 0))
        );

        List<TravelPhotoClusterService.PhotoCluster> clusters = clusterService.cluster(points);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).representative().mediaId()).isEqualTo(2L);
        assertThat(clusters.get(0).members().get(0).mediaId()).isEqualTo(2L);
    }

    private TravelPhotoClusterService.PhotoPoint point(
            Long mediaId,
            double latitude,
            double longitude,
            boolean representativeOverride,
            LocalDateTime uploadedAt
    ) {
        return new TravelPhotoClusterService.PhotoPoint(
                mediaId,
                mediaId,
                1L,
                "Test plan",
                "#3182F6",
                LocalDate.of(2026, 4, 4),
                LocalTime.of(10, 0),
                "Spot",
                "Photo " + mediaId,
                "KR",
                "Seoul",
                "Test",
                BigDecimal.valueOf(latitude).setScale(7, java.math.RoundingMode.HALF_UP),
                BigDecimal.valueOf(longitude).setScale(7, java.math.RoundingMode.HALF_UP),
                uploadedAt,
                representativeOverride
        );
    }

    private double toLatitudeDegrees(double meters) {
        return meters / 111_320d;
    }
}

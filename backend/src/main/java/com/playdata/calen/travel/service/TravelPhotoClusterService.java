package com.playdata.calen.travel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TravelPhotoClusterService {

    private static final double BASE_CLUSTER_DISTANCE_METERS = 2.0d;
    private static final double MAX_CLUSTER_DISTANCE_FROM_CENTER_METERS = 10.0d;
    private static final Comparator<PhotoPoint> REPRESENTATIVE_OVERRIDE_ORDER = Comparator
            .comparing(PhotoPoint::uploadedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PhotoPoint::mediaId, Comparator.nullsLast(Comparator.reverseOrder()));
    private static final Comparator<PhotoPoint> CLUSTER_ORDER = Comparator
            .comparing(PhotoPoint::memoryDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PhotoPoint::memoryTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PhotoPoint::uploadedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PhotoPoint::mediaId, Comparator.nullsLast(Comparator.reverseOrder()));

    public List<PhotoCluster> cluster(List<PhotoPoint> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        List<List<PhotoPoint>> baseClusters = buildConnectedComponents(points, BASE_CLUSTER_DISTANCE_METERS);
        List<List<PhotoPoint>> normalizedClusters = new ArrayList<>();
        baseClusters.forEach(cluster -> splitOversizedCluster(cluster, normalizedClusters));

        return normalizedClusters.stream()
                .map(this::toPhotoCluster)
                .sorted(Comparator.comparing(PhotoCluster::representative, CLUSTER_ORDER))
                .toList();
    }

    private List<List<PhotoPoint>> buildConnectedComponents(List<PhotoPoint> points, double distanceThresholdMeters) {
        List<List<PhotoPoint>> clusters = new ArrayList<>();
        boolean[] visited = new boolean[points.size()];

        for (int index = 0; index < points.size(); index += 1) {
            if (visited[index]) {
                continue;
            }

            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(index);
            visited[index] = true;

            List<PhotoPoint> cluster = new ArrayList<>();
            while (!queue.isEmpty()) {
                int currentIndex = queue.removeFirst();
                PhotoPoint currentPoint = points.get(currentIndex);
                cluster.add(currentPoint);

                for (int candidateIndex = 0; candidateIndex < points.size(); candidateIndex += 1) {
                    if (visited[candidateIndex]) {
                        continue;
                    }
                    if (calculateDistanceMeters(currentPoint, points.get(candidateIndex)) <= distanceThresholdMeters) {
                        visited[candidateIndex] = true;
                        queue.add(candidateIndex);
                    }
                }
            }

            clusters.add(cluster);
        }

        return clusters;
    }

    private void splitOversizedCluster(List<PhotoPoint> points, List<List<PhotoPoint>> sink) {
        if (points == null || points.isEmpty()) {
            return;
        }

        if (points.size() <= 1) {
            sink.add(List.copyOf(points));
            return;
        }

        ClusterMetrics metrics = calculateClusterMetrics(points);
        if (metrics.maxDistanceMeters() <= MAX_CLUSTER_DISTANCE_FROM_CENTER_METERS) {
            sink.add(List.copyOf(points));
            return;
        }

        SplitResult splitResult = splitCluster(points, metrics);
        if (splitResult.left().isEmpty()
                || splitResult.right().isEmpty()
                || splitResult.left().size() == points.size()
                || splitResult.right().size() == points.size()) {
            sink.add(List.copyOf(points));
            return;
        }

        splitOversizedCluster(splitResult.left(), sink);
        splitOversizedCluster(splitResult.right(), sink);
    }

    private SplitResult splitCluster(List<PhotoPoint> points, ClusterMetrics metrics) {
        PhotoPoint seedA = points.stream()
                .max(Comparator.comparingDouble(point -> calculateDistanceMeters(point, metrics.centroidLatitude(), metrics.centroidLongitude())))
                .orElse(points.get(0));
        PhotoPoint seedB = points.stream()
                .max(Comparator.comparingDouble(point -> calculateDistanceMeters(point, seedA)))
                .orElse(seedA);

        if (seedA.mediaId().equals(seedB.mediaId())) {
            return splitByDominantAxis(points, metrics);
        }

        List<PhotoPoint> left = new ArrayList<>();
        List<PhotoPoint> right = new ArrayList<>();

        for (PhotoPoint point : points) {
            double distanceToA = calculateDistanceMeters(point, seedA);
            double distanceToB = calculateDistanceMeters(point, seedB);
            if (distanceToA <= distanceToB) {
                left.add(point);
            } else {
                right.add(point);
            }
        }

        if (left.isEmpty() || right.isEmpty()) {
            return splitByDominantAxis(points, metrics);
        }

        return new SplitResult(left, right);
    }

    private SplitResult splitByDominantAxis(List<PhotoPoint> points, ClusterMetrics metrics) {
        List<PhotoPoint> sorted = new ArrayList<>(points);
        double latitudeRangeMeters = Math.abs(metrics.maxLatitude() - metrics.minLatitude()) * 111_320d;
        double longitudeRangeMeters = Math.abs(metrics.maxLongitude() - metrics.minLongitude())
                * 111_320d
                * Math.cos(Math.toRadians(metrics.centroidLatitude()));

        if (longitudeRangeMeters > latitudeRangeMeters) {
            sorted.sort(Comparator.comparing(PhotoPoint::longitude).thenComparing(PhotoPoint::mediaId));
        } else {
            sorted.sort(Comparator.comparing(PhotoPoint::latitude).thenComparing(PhotoPoint::mediaId));
        }

        int midpoint = Math.max(1, sorted.size() / 2);
        return new SplitResult(
                new ArrayList<>(sorted.subList(0, midpoint)),
                new ArrayList<>(sorted.subList(midpoint, sorted.size()))
        );
    }

    private PhotoCluster toPhotoCluster(List<PhotoPoint> points) {
        ClusterMetrics metrics = calculateClusterMetrics(points);
        PhotoPoint representative = selectRepresentative(points, metrics);
        int memoryCount = (int) points.stream()
                .map(PhotoPoint::recordId)
                .distinct()
                .count();

        List<PhotoPoint> orderedMembers = points.stream()
                .sorted(Comparator
                        .comparing((PhotoPoint point) -> !point.mediaId().equals(representative.mediaId()))
                        .thenComparing(CLUSTER_ORDER))
                .toList();

        return new PhotoCluster(
                representative.mediaId(),
                representative,
                orderedMembers,
                BigDecimal.valueOf(metrics.centroidLatitude()).setScale(7, RoundingMode.HALF_UP),
                BigDecimal.valueOf(metrics.centroidLongitude()).setScale(7, RoundingMode.HALF_UP),
                BigDecimal.valueOf(metrics.maxDistanceMeters()).setScale(2, RoundingMode.HALF_UP),
                memoryCount
        );
    }

    private PhotoPoint selectRepresentative(List<PhotoPoint> points, ClusterMetrics metrics) {
        List<PhotoPoint> overrides = points.stream()
                .filter(PhotoPoint::representativeOverride)
                .sorted(REPRESENTATIVE_OVERRIDE_ORDER)
                .toList();
        if (!overrides.isEmpty()) {
            return overrides.get(0);
        }

        return points.stream()
                .sorted(CLUSTER_ORDER)
                .findFirst()
                .orElse(points.get(0));
    }

    private ClusterMetrics calculateClusterMetrics(List<PhotoPoint> points) {
        double latitudeSum = 0.0d;
        double longitudeSum = 0.0d;
        double minLatitude = Double.POSITIVE_INFINITY;
        double maxLatitude = Double.NEGATIVE_INFINITY;
        double minLongitude = Double.POSITIVE_INFINITY;
        double maxLongitude = Double.NEGATIVE_INFINITY;

        for (PhotoPoint point : points) {
            double latitude = point.latitude().doubleValue();
            double longitude = point.longitude().doubleValue();
            latitudeSum += latitude;
            longitudeSum += longitude;
            minLatitude = Math.min(minLatitude, latitude);
            maxLatitude = Math.max(maxLatitude, latitude);
            minLongitude = Math.min(minLongitude, longitude);
            maxLongitude = Math.max(maxLongitude, longitude);
        }

        double centroidLatitude = latitudeSum / points.size();
        double centroidLongitude = longitudeSum / points.size();
        double maxDistanceMeters = points.stream()
                .mapToDouble(point -> calculateDistanceMeters(point, centroidLatitude, centroidLongitude))
                .max()
                .orElse(0.0d);

        return new ClusterMetrics(
                centroidLatitude,
                centroidLongitude,
                maxDistanceMeters,
                minLatitude,
                maxLatitude,
                minLongitude,
                maxLongitude
        );
    }

    private double calculateDistanceMeters(PhotoPoint left, PhotoPoint right) {
        return calculateDistanceMeters(left, right.latitude().doubleValue(), right.longitude().doubleValue());
    }

    private double calculateDistanceMeters(PhotoPoint point, double latitude, double longitude) {
        double latitudeDistance = Math.toRadians(latitude - point.latitude().doubleValue());
        double longitudeDistance = Math.toRadians(longitude - point.longitude().doubleValue());
        double startLatitude = Math.toRadians(point.latitude().doubleValue());
        double endLatitude = Math.toRadians(latitude);

        double haversine = Math.pow(Math.sin(latitudeDistance / 2.0d), 2)
                + Math.cos(startLatitude) * Math.cos(endLatitude) * Math.pow(Math.sin(longitudeDistance / 2.0d), 2);
        double angularDistance = 2.0d * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0d - haversine));
        return 6_371_000d * angularDistance;
    }

    private record ClusterMetrics(
            double centroidLatitude,
            double centroidLongitude,
            double maxDistanceMeters,
            double minLatitude,
            double maxLatitude,
            double minLongitude,
            double maxLongitude
    ) {
    }

    private record SplitResult(
            List<PhotoPoint> left,
            List<PhotoPoint> right
    ) {
    }

    public record PhotoPoint(
            Long mediaId,
            Long recordId,
            Long planId,
            String planName,
            String planColorHex,
            LocalDate memoryDate,
            LocalTime memoryTime,
            String category,
            String title,
            String country,
            String region,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDateTime uploadedAt,
            boolean representativeOverride
    ) {
    }

    public record PhotoCluster(
            Long id,
            PhotoPoint representative,
            List<PhotoPoint> members,
            BigDecimal centroidLatitude,
            BigDecimal centroidLongitude,
            BigDecimal maxDistanceMeters,
            int memoryCount
    ) {
        public int photoCount() {
            return members == null ? 0 : members.size();
        }

        public Set<Long> memberMediaIds() {
            Set<Long> ids = new HashSet<>();
            if (members != null) {
                members.forEach(member -> ids.add(member.mediaId()));
            }
            return ids;
        }
    }
}

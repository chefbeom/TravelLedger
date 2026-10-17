package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.model.TravelRouteData.Point;
import java.util.*;

public final class TravelRoutePolicy {
    private static final int MAX_STORED_ROUTE_POINTS = 900;
    private static final String ROUTE_POINT_TYPE_ROUTE = "ROUTE", ROUTE_POINT_TYPE_MEMORY = "MEMORY";
    private TravelRoutePolicy() {}
    public static String lineColor(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return TravelPlanPolicy.color(normalized.isEmpty() ? fallback : normalized);
    }
    public static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed=value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    public static void validateRoutePoints(List<Point> points) {
        if (points == null || points.size() < 2) {
            throw new BadRequestException("At least two route points are required.");
        }

        points.forEach(point -> {
            TravelCoordinatePolicy.normalize(point.latitude(), true);
            TravelCoordinatePolicy.normalize(point.longitude(), false);
        });
    }

    public static List<Point> normalizeRoutePointsForStorage(List<Point> points, TravelRouteSourceType sourceType) {
        validateRoutePoints(points);

        List<Point> normalized = points.stream()
                .map(point -> new Point(
                        TravelCoordinatePolicy.normalize(point.latitude(), true),
                        TravelCoordinatePolicy.normalize(point.longitude(), false),
                        normalizeRoutePointType(point.pointType()),
                        point.linkedMemoryId(),
                        trimRoutePointLabel(point.label())
                ))
                .toList();

        List<Point> deduplicated = deduplicateRoutePoints(normalized);
        if (deduplicated.size() < 2) {
            throw new BadRequestException("At least two distinct route points are required.");
        }

        List<Point> candidate = deduplicated;
        if (sourceType == TravelRouteSourceType.GPX && deduplicated.size() > MAX_STORED_ROUTE_POINTS) {
            candidate = thinRoutePoints(deduplicated, MAX_STORED_ROUTE_POINTS);
        } else if (deduplicated.size() > MAX_STORED_ROUTE_POINTS * 2) {
            candidate = thinRoutePoints(deduplicated, MAX_STORED_ROUTE_POINTS * 2);
        }

        return candidate;
    }

    public static String normalizeRoutePointType(String pointType) {
        String normalized = trimToNull(pointType);
        if (normalized == null) {
            return ROUTE_POINT_TYPE_ROUTE;
        }
        return ROUTE_POINT_TYPE_MEMORY.equalsIgnoreCase(normalized)
                ? ROUTE_POINT_TYPE_MEMORY
                : ROUTE_POINT_TYPE_ROUTE;
    }

    public static String trimRoutePointLabel(String label) {
        String trimmed = trimToNull(label);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }

    public static List<Point> deduplicateRoutePoints(List<Point> points) {
        List<Point> deduplicated = new ArrayList<>();

        for (Point point : points) {
            Point previous = deduplicated.isEmpty() ? null : deduplicated.get(deduplicated.size() - 1);
            if (previous != null
                    && previous.latitude().compareTo(point.latitude()) == 0
                    && previous.longitude().compareTo(point.longitude()) == 0) {
                continue;
            }
            deduplicated.add(point);
        }

        return List.copyOf(deduplicated);
    }

    public static List<Point> thinRoutePoints(List<Point> points, int maxPoints) {
        if (points.size() <= maxPoints) {
            return points;
        }

        List<Point> reduced = new ArrayList<>();
        int step = Math.max(1, (int) Math.ceil((double) (points.size() - 1) / Math.max(1, maxPoints - 1)));

        for (int index = 0; index < points.size(); index += step) {
            reduced.add(points.get(index));
        }

        Point lastPoint = points.get(points.size() - 1);
        Point reducedLastPoint = reduced.get(reduced.size() - 1);
        if (reducedLastPoint.latitude().compareTo(lastPoint.latitude()) != 0
                || reducedLastPoint.longitude().compareTo(lastPoint.longitude()) != 0) {
            reduced.add(lastPoint);
        }

        return List.copyOf(reduced);
    }
}

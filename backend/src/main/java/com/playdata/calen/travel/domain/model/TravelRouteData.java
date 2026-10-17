package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TravelRouteData {
    private TravelRouteData() {}
    public record Point(BigDecimal latitude, BigDecimal longitude, String pointType, Long linkedMemoryId, String label) {
        public Point(BigDecimal latitude, BigDecimal longitude) { this(latitude,longitude,null,null,null); }
    }
    public record GpxFile(String originalFileName, String storagePath, String contentType, long fileSize) {}
    public record Plan(Long id, String name, String colorHex) {}
    public record Access(Long id, Plan plan) {}
    public record Draft(LocalDate routeDate, String title, TravelRouteTransportMode transportMode, BigDecimal distanceKm,
            Integer durationMinutes, Integer stepCount, TravelRouteSourceType sourceType, String startPlaceName,
            String endPlaceName, String lineColorHex, TravelRouteLineStyle lineStyle, String memo, List<Point> points) {}
    /** path is an opaque storage snapshot produced by the payload port, not parsed in the core. */
    public record Value(LocalDate routeDate, String title, TravelRouteTransportMode transportMode, BigDecimal distanceKm,
            Integer durationMinutes, Integer stepCount, TravelRouteSourceType sourceType, String startPlaceName,
            String endPlaceName, String path, String lineColorHex, TravelRouteLineStyle lineStyle, String memo) {}
    public record Result(Long id, Plan plan, LocalDate routeDate, String title, TravelRouteTransportMode transportMode,
            BigDecimal distanceKm, Integer durationMinutes, Integer stepCount, TravelRouteSourceType sourceType,
            String startPlaceName, String endPlaceName, String lineColorHex, TravelRouteLineStyle lineStyle,
            List<String> gpxFileNames, String memo, List<Point> points) {}
}

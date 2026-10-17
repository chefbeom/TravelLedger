package com.playdata.calen.travel.service;

/** Bounding rectangle that contains a spherical radius, including poles and the date line. */
public record GeoBounds(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude,
                        boolean allLongitudes, boolean crossesDateLine) {
    public static GeoBounds around(double latitude, double longitude, double radiusMeters) {
        var bounds = com.playdata.calen.travel.domain.policy.TravelMapGeometry.around(latitude, longitude, radiusMeters);
        return new GeoBounds(bounds.minLatitude(), bounds.maxLatitude(), bounds.minLongitude(), bounds.maxLongitude(), bounds.allLongitudes(), bounds.crossesDateLine());
    }
}

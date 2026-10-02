package com.playdata.calen.travel.service;

/** Bounding rectangle that contains a spherical radius, including poles and the date line. */
public record GeoBounds(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude,
                        boolean allLongitudes, boolean crossesDateLine) {
    public static GeoBounds around(double latitude, double longitude, double radiusMeters) {
        double angular = Math.min(Math.PI, radiusMeters / 6_371_000d);
        double lat = Math.toRadians(latitude);
        double min = Math.max(-Math.PI / 2, lat - angular);
        double max = Math.min(Math.PI / 2, lat + angular);
        if (min <= -Math.PI / 2 || max >= Math.PI / 2) {
            return new GeoBounds(Math.toDegrees(min), Math.toDegrees(max), -180, 180, true, false);
        }
        double delta = Math.toDegrees(Math.asin(Math.min(1, Math.sin(angular) / Math.cos(lat))));
        double west = normalize(longitude - delta);
        double east = normalize(longitude + delta);
        return new GeoBounds(Math.toDegrees(min), Math.toDegrees(max), west, east, false, west > east);
    }

    private static double normalize(double longitude) {
        return ((longitude + 180) % 360 + 360) % 360 - 180;
    }
}

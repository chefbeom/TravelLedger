package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;

public final class TravelMapGeometry {
    private TravelMapGeometry() {}
    public static final double MAX_RADIUS_METERS = Math.PI * 6_371_000d;
    public static Bounds around(double latitude, double longitude, double radiusMeters) {
        double angular = Math.min(Math.PI, radiusMeters / 6_371_000d);
        double lat = Math.toRadians(latitude);
        double min = Math.max(-Math.PI / 2, lat - angular);
        double max = Math.min(Math.PI / 2, lat + angular);
        if (min <= -Math.PI / 2 || max >= Math.PI / 2) {
            return new Bounds(Math.toDegrees(min), Math.toDegrees(max), -180, 180, true, false);
        }
        double delta = Math.toDegrees(Math.asin(Math.min(1, Math.sin(angular) / Math.cos(lat))));
        double west = normalize(longitude - delta);
        double east = normalize(longitude + delta);
        return new Bounds(Math.toDegrees(min), Math.toDegrees(max), west, east, false, west > east);
    }
    private static double normalize(double longitude) { return ((longitude + 180) % 360 + 360) % 360 - 180; }
    public static double distance(Location origin, Location target) {
        double originLatitude = origin.latitude().doubleValue();
        double originLongitude = origin.longitude().doubleValue();
        double targetLatitude = target.latitude().doubleValue();
        double targetLongitude = target.longitude().doubleValue();
        double latitudeDistance = Math.toRadians(targetLatitude - originLatitude);
        double longitudeDistance = Math.toRadians(targetLongitude - originLongitude);
        double startLatitude = Math.toRadians(originLatitude);
        double endLatitude = Math.toRadians(targetLatitude);
        double haversine = Math.pow(Math.sin(latitudeDistance / 2), 2)
                + Math.cos(startLatitude) * Math.cos(endLatitude) * Math.pow(Math.sin(longitudeDistance / 2), 2);
        double angularDistance = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        return 6371000d * angularDistance;
    }
}

package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.service.GeoBounds;
import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TravelMapGeometryTest {
    @Test void detachedPolicyAndLegacyFacadeMatchTheFrozenHeadFormulaAcrossPolesAndDateLine() {
        var random = new Random(20261016);
        for (int i = 0; i < 4000; i++) {
            double lat = i < 3 ? new double[] {-90, 0, 90}[i] : random.nextDouble() * 180 - 90;
            double lon = i < 3 ? 180 : random.nextDouble() * 360 - 180;
            double radius = i < 3 ? TravelMapGeometry.MAX_RADIUS_METERS : random.nextDouble() * TravelMapGeometry.MAX_RADIUS_METERS;
            var expected = headBounds(lat, lon, radius);
            assertThat(TravelMapGeometry.around(lat, lon, radius)).isEqualTo(expected);
            var facade = GeoBounds.around(lat, lon, radius);
            assertThat(new Bounds(facade.minLatitude(), facade.maxLatitude(), facade.minLongitude(), facade.maxLongitude(), facade.allLongitudes(), facade.crossesDateLine()))
                    .isEqualTo(expected);
        }
        assertThat(TravelMapGeometry.around(0, 179.9999, 100).crossesDateLine()).isTrue();
        assertThat(TravelMapGeometry.around(89.9999, 0, 100).allLongitudes()).isTrue();
    }

    @Test void haversineDistancePreservesCoincidentAndDateLineBehavior() {
        var a = new Location(1L, BigDecimal.ZERO, new BigDecimal("179.9999"));
        var b = new Location(2L, BigDecimal.ZERO, new BigDecimal("-179.9999"));
        assertThat(TravelMapGeometry.distance(a, a)).isZero();
        assertThat(TravelMapGeometry.distance(a, b)).isBetween(22.23, 22.25);
        assertThat(TravelMapGeometry.distance(a, b)).isEqualTo(TravelMapGeometry.distance(b, a));
    }

    /** Frozen from HEAD 4aeb6d7d GeoBounds; deliberately independent of the production policy. */
    private static Bounds headBounds(double latitude, double longitude, double radiusMeters) {
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
}

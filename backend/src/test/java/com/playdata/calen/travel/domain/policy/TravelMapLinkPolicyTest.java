package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.Point;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TravelMapLinkPolicyTest {
    @Test void selectedPlansMemoryPhotoExclusionsAndEitherCoordinateSourceRemainIndependentConditions() {
        for (int bits = 0; bits < 128; bits++) {
            boolean selected = (bits & 1) != 0, memory = (bits & 2) != 0, photo = (bits & 4) != 0;
            boolean excludedRecord = (bits & 8) != 0, excludedMedia = (bits & 16) != 0;
            boolean recordCoordinates = (bits & 32) != 0, gpsCoordinates = (bits & 64) != 0;
            var scope = new Scope(List.of(10L), excludedRecord ? Set.of(20L) : Set.of(), excludedMedia ? Set.of(30L) : Set.of(), Set.of());
            var asset = new Asset(30L, selected ? 10L : 11L, 20L, memory ? TravelRecordType.MEMORY : TravelRecordType.LEDGER,
                    photo ? TravelMediaType.PHOTO : TravelMediaType.RECEIPT, recordCoordinates, gpsCoordinates, null);
            assertThat(TravelMapLinkPolicy.visible(asset, Set.of(10L), scope)).isEqualTo(selected && memory && photo && !excludedRecord && !excludedMedia && (recordCoordinates || gpsCoordinates));
        }
    }

    @Test void previewSamplingKeepsTheLegacyEndpointEvenWhenItProduces161PointsAndDropsPrivatePointFields() {
        var points = IntStream.range(0, 320).mapToObj(i -> new Point(new BigDecimal("35.123456").add(BigDecimal.valueOf(i).movePointLeft(3)),
                new BigDecimal("129.987654"), "MEMORY", 99L, "Private label")).toList();
        var sampled = TravelMapLinkPolicy.sample(points);
        assertThat(sampled).hasSize(161);
        assertThat(sampled.get(0)).isEqualTo(new PreviewPoint(new BigDecimal("35.123"), new BigDecimal("129.988")));
        assertThat(sampled.get(sampled.size() - 1)).isEqualTo(new PreviewPoint(TravelMapLinkPolicy.rounded(points.get(319).latitude()), new BigDecimal("129.988")));
        assertThat(TravelMapLinkPolicy.sample(Arrays.asList(null, new Point(null, null), points.get(0)))).isEmpty();
        assertThat(Arrays.stream(PreviewPoint.class.getRecordComponents()).map(component -> component.getName())).containsExactly("latitude", "longitude");
    }
}

package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.travel.adapter.out.cache.OwnedTravelMapCache.VersionedMapOverview;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import org.junit.jupiter.api.Test;

class TravelOwnedMapMapperTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void cacheRoundTripPreservesAllNestedFieldsNullsOrderScalesAndOpaqueUrls() throws Exception {
        for (int seed = 0; seed < 128; seed++) {
            ObjectNode tree = (ObjectNode) json.readTree(FULL);
            if ((seed & 1) != 0) tree.putNull("markers");
            if ((seed & 2) != 0) tree.putNull("photoClusters");
            if ((seed & 4) != 0) tree.withArray("photoPins").addNull();
            if ((seed & 8) != 0) ((ObjectNode) tree.get("routes").get(0)).putNull("points");
            if ((seed & 16) != 0) ((ObjectNode) tree.get("routes").get(0)).putNull("durationMinutes");
            if ((seed & 32) != 0) ((ObjectNode) tree.get("routes").get(0)).putNull("gpxFileNames");
            if ((seed & 64) != 0) tree.putNull("totalDistanceKm");
            var dto = json.treeToValue(tree, TravelMyMapOverviewResponse.class);
            var mapped = TravelOwnedMapMapper.response(TravelOwnedMapMapper.value(dto));
            assertThat(json.writeValueAsString(mapped)).as("cache fixture %d", seed).isEqualTo(json.writeValueAsString(dto));
        }
        assertThat(TravelOwnedMapMapper.value((TravelMyMapOverviewResponse) null)).isNull();
        assertThat(TravelOwnedMapMapper.response((com.playdata.calen.travel.domain.model.TravelOwnedMapData.Overview) null)).isNull();
    }

    @Test void formerPrivateEnvelopeCanBeReadWithoutAJavaTypeIdAndRetainsItsJsonContract() throws Exception {
        String oldWire = "{\"generation\":29,\"overview\":" + FULL + "}";
        var decoded = json.readValue(oldWire, VersionedMapOverview.class);
        assertThat(decoded.generation()).isEqualTo(29);
        assertThat(json.readTree(json.writeValueAsString(decoded))).isEqualTo(json.readTree(oldWire));
        assertThat(json.writeValueAsString(decoded)).doesNotContain("@class", "TravelService", "VersionedMapOverview");
    }

    static final String FULL = """
        {"includedPlanCount":2,"markerCount":3,"photoMarkerCount":4,"photoClusterCount":1,"routeCount":1,"totalDistanceKm":4.123,
         "markers":[{"id":1,"planId":2,"planName":"Trip","planColorHex":"opaque-old-color","memoryDate":"2026-10-16","memoryTime":"12:34:56",
           "category":"Place","title":"Title","country":"KR","region":"Seoul","placeName":"A","photoUrl":"/old/cached?media=1",
           "latitude":37.1234567,"longitude":127.1234567},null],
         "photoClusters":[{"id":3,"representativeMediaId":4,"representativeRecordId":5,"planId":2,"planName":"Trip","planColorHex":"#abcdef",
           "memoryDate":"2026-10-16","memoryTime":"01:02:03","category":"Place","title":"Cluster","country":"KR","region":"Seoul","placeName":"B",
           "latitude":37.1234567,"longitude":127.1234567,"photoCount":4,"memoryCount":3,"maxDistanceMeters":15.120,"representativeOverride":true,
           "representativePhotoUrl":"https://cached.invalid/image?legacy=true"},null],
         "photoPins":[{"mediaId":4,"clusterId":3,"recordId":5,"planId":2,"planName":"Trip","planColorHex":null,"memoryDate":null,"memoryTime":null,
           "category":null,"title":"Pin","country":"KR","region":"Seoul","placeName":"C","latitude":37.1,"longitude":127.1,
           "photoUrl":"/unmodified/pin","representative":true,"representativeOverride":false}],
         "routes":[{"id":6,"planId":2,"planName":"Trip","planColorHex":"#abcdef","routeDate":"2026-10-16","title":"Path",
           "transportMode":"WALK","distanceKm":4.123,"durationMinutes":0,"stepCount":123,"sourceType":"MANUAL",
           "startPlaceName":"A","endPlaceName":"B","lineColorHex":"cached-not-normalized","lineStyle":"SOLID",
           "gpxFileNames":[null,"","path.gpx"],"memo":"Memo","points":[{"latitude":37.1234567,"longitude":127.1234567,
             "pointType":"MEMORY","linkedMemoryId":5,"label":"Point"},null]},null]}
        """;
}

package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.service.TravelMyMapPhotoClusterSnapshot;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelClusterSnapshotMapperTest {
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    @Test void snapshotIsByteCompatibleWithExistingSummaryAndPinContracts() throws Exception {
        LocalDate date = LocalDate.of(2026,10,15); LocalTime time = LocalTime.of(12,3,4);
        BigDecimal lat = new BigDecimal("37.5000000"), lon = new BigDecimal("127.0000000"), distance = new BigDecimal("1.23");
        var summary = new Summary(1L, 2L, 3L, 4L, "Trip", "#abcdef", date, time, "Spot", "Place", "JP", null, "Cafe", lat, lon, 5, 2, distance, true);
        var pin = new Pin(2L, 1L, 3L, 4L, "Trip", "#ABCDEF", date, time, "Spot", "Place", "JP", null, "Cafe", lat, lon, true, true);
        var expectedSummary = new TravelMyMapPhotoClusterSummaryResponse(1L,2L,3L,4L,"Trip","#abcdef",date,time,"Spot","Place","JP",null,"Cafe",lat,lon,5,2,distance,true,"/api/travel/media/2/content");
        var expectedPin = new TravelMyMapPhotoPinResponse(2L,1L,3L,4L,"Trip","#ABCDEF",date,time,"Spot","Place","JP",null,"Cafe",lat,lon,"/api/travel/media/2/content",true,true);
        var expected = new TravelMyMapPhotoClusterSnapshot(5,1,List.of(expectedSummary),List.of(),List.of(expectedPin));
        assertThat(json.writeValueAsBytes(TravelClusterSnapshotMapper.response(new Snapshot(5,1,List.of(summary),List.of(pin)))))
                .isEqualTo(json.writeValueAsBytes(expected));
    }
    @Test void nullAndEmptyStoredProjectionKeepLegacyMeanings() {
        assertThat(TravelClusterSnapshotMapper.response(null)).isNull();
        assertThat(TravelClusterSnapshotMapper.response(Snapshot.empty())).isEqualTo(new TravelMyMapPhotoClusterSnapshot(0,0,List.of(),List.of(),List.of()));
    }
}

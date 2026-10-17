package com.playdata.calen.recordmap.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class RecordMapReadMapperTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final LocalDate date = LocalDate.of(2026, 10, 15);
    private final LocalTime time = LocalTime.of(12, 30, 45);

    @ParameterizedTest @MethodSource("photoRoutes")
    void visitIdsPhotosAndUrlsMatchLegacyContract(Origin origin, String token, String visitId, String photoId, String url) throws Exception {
        var visit = new VisitResult(7L, origin, 2L, "여행 café", "메모", date, time,
                origin == Origin.TRAVEL ? 8L : null, origin == Origin.TRAVEL ? "여행" : null, List.of(new PhotoData(9L, "사진.jpg", origin)));
        var expected = new VisitView(visitId, 2L, "여행 café", "메모", date, time, visit.travelPlanId(), visit.travelPlanName(),
                List.of(new PhotoView(photoId, "사진.jpg", url)));
        assertThat(json.writeValueAsBytes(RecordMapReadMapper.visits(List.of(visit), token)))
                .isEqualTo(json.writeValueAsBytes(List.of(expected)));
    }

    @Test void overviewFieldNamesNullsOrderingAndFlagsRemainTheSame() throws Exception {
        var source = new OverviewResult(List.of(new CollectionResult(3L, "collection", null, "#009E8C", 501, true)),
                List.of(new PlaceResult(2L, "place", null, "Japan", "Osaka", 34.5, 135.5, true, 0, null, List.of(3L))),
                List.of(), 501, 0, 2, false, true, false, "collection", true);
        var expected = new Overview(List.of(new CollectionView(3L, "collection", null, "#009E8C", 501, true)),
                List.of(new PlaceView(2L, "place", null, "Japan", "Osaka", 34.5, 135.5, true, 0, null, List.of(3L))),
                List.of(), 501, 0, 2, false, true, false, "collection", true);
        assertThat(json.writeValueAsBytes(RecordMapReadMapper.response(source, "token"))).isEqualTo(json.writeValueAsBytes(expected));
    }

    @Test void travelCandidatePageUsesTheSameSpringPageEnvelope() throws Exception {
        var candidate = new TravelCandidateData(7L, 8L, "trip", "memory", date, "Japan", "Osaka", 34.5, 135.5);
        var source = new CandidatePage(List.of(candidate), new Window(2, 30), 61);
        var expected = new PageImpl<>(List.of(new TravelCandidate(7L, 8L, "trip", "memory", date, "Japan", "Osaka", 34.5, 135.5)), PageRequest.of(2, 30), 61);
        assertThat(json.writeValueAsBytes(RecordMapReadMapper.candidates(source))).isEqualTo(json.writeValueAsBytes(expected));
    }

    private static Stream<Arguments> photoRoutes() {
        return Stream.of(Arguments.of(Origin.RECORD_MAP, null, "visit:7", "photo:9", "/api/record-map/photos/9/content"),
                Arguments.of(Origin.RECORD_MAP, "token", "visit:7", "photo:9", "/api/record-map/public/token/photos/9/content"),
                Arguments.of(Origin.TRAVEL, null, "travel:7", "travel-photo:9", "/api/travel/media/9/content"),
                Arguments.of(Origin.TRAVEL, "token", "travel:7", "travel-photo:9", "/api/record-map/public/token/travel-photos/9/content"));
    }
}

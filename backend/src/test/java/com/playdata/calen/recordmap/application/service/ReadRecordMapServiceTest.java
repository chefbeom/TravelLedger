package com.playdata.calen.recordmap.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.application.port.out.RecordMapQueryPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapReadAccessPort;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReadRecordMapServiceTest {
    private final RecordMapReadAccessPort access = mock(RecordMapReadAccessPort.class);
    private final RecordMapQueryPort query = mock(RecordMapQueryPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private final ReadRecordMapService service = new ReadRecordMapService(access, query, clock);
    private final LocalDate date = LocalDate.of(2026, 10, 15);
    private final Scope personal = new Scope(1L, null, false, true);

    @Test void ownedSearchUsesNormalizedKeywordPageAndOwnerCollections() {
        when(access.owned(1L, null)).thenReturn(personal);
        var selection = new Selection("ALL", null, null, "Book!_%", new Window(2, 30));
        when(query.places(personal, selection, true)).thenReturn(new PlacePage(List.of(place(null)), 61, 3));
        when(query.visitCounts(1L, List.of(2L))).thenReturn(Map.of(2L, 3L));
        when(query.collectionCounts(1L)).thenReturn(Map.of(3L, 9L));
        when(query.sharedCollections(1L, LocalDateTime.now(clock))).thenReturn(Set.of(3L));
        when(query.collections(1L)).thenReturn(List.of(new CollectionData(3L, "saved", "collection memo", "#009E8C")));
        var result = service.owned(1L, null, new Filter("ALL", null, null, 2, " Book!_% ", 30, false));
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPlaces()).isEqualTo(61);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.placesLimited()).isFalse();
        assertThat(result.places().get(0).memo()).isEqualTo("private place memo");
        assertThat(result.places().get(0).visitCount()).isEqualTo(3);
        assertThat(result.collections().get(0)).isEqualTo(new CollectionResult(3L, "saved", "collection memo", "#009E8C", 9, true));
        var order = inOrder(access, query);
        order.verify(access).owned(1L, null);
        order.verify(query).places(personal, selection, true);
        verify(query, never()).travelRecords(any(), any());
    }

    @Test void publicPlacesOnlyNeverSearchOrReturnPrivateFieldsOrFetchPhotoBodies() {
        var scope = new Scope(1L, 3L, true, false);
        when(access.shared("token")).thenReturn(scope);
        when(query.places(eq(scope), any(), eq(false))).thenReturn(new PlacePage(List.of(place(100L)), 501, 2));
        when(query.visitCounts(1L, List.of(2L))).thenReturn(Map.of(2L, 2L));
        when(query.travelRecords(1L, List.of(100L))).thenReturn(Map.of(100L, original(date)));
        when(query.collection(1L, 3L)).thenReturn(new CollectionData(3L, "public map", "secret collection memo", "#009E8C"));
        var result = service.shared("token", new Filter("ALL", null, null, 5, "search", 30, true));
        assertThat(result.readOnly()).isTrue();
        assertThat(result.visitsIncluded()).isFalse();
        assertThat(result.visits()).isEmpty();
        assertThat(result.page()).isZero();
        assertThat(result.placesLimited()).isTrue();
        assertThat(result.title()).isEqualTo("public map");
        var place = result.places().get(0);
        assertThat(place.visited()).isTrue();
        assertThat(place.visitCount()).isZero();
        assertThat(place.memo()).isNull();
        assertThat(place.sourceTravelRecordId()).isNull();
        assertThat(place.collectionIds()).containsExactly(3L);
        assertThat(result.collections().get(0).memo()).isNull();
        assertThat(result.collections().get(0).placeCount()).isEqualTo(501);
        verify(query).places(scope, new Selection("ALL", null, null, "search", new Window(0, 500)), false);
        verify(query, never()).visits(any(), any(), any(), any(), anyInt());
        verify(query, never()).visitPhotos(any(), any());
        verify(query, never()).travelPhotos(any(), any(), anyInt());
        verify(query, never()).collections(any());
        verify(query, never()).collectionCounts(any());
        verify(query, never()).sharedCollections(any(), any());
    }

    @Test void visitLimitIsAppliedBeforePhotoBatchAndTravelVisitsFollowPersonalVisits() {
        when(access.owned(1L, null)).thenReturn(personal);
        when(query.places(eq(personal), any(), eq(true))).thenReturn(new PlacePage(List.of(place(100L)), 1, 1));
        var rows = IntStream.rangeClosed(1, 201).mapToObj(i -> new VisitData((long)i, 2L, "visit-"+i, "memo", date, null)).toList();
        when(query.visits(1L, List.of(2L), date, date, 201)).thenReturn(rows);
        var ids = rows.subList(0, 200).stream().map(VisitData::id).toList();
        when(query.visitPhotos(1L, ids)).thenReturn(Map.of(1L, List.of(new PhotoData(9L, "visit.jpg", Origin.RECORD_MAP))));
        when(query.travelRecords(1L, List.of(100L))).thenReturn(Map.of(100L, original(date)));
        when(query.travelPhotos(1L, List.of(100L), 1000)).thenReturn(List.of(new TravelPhoto(100L, new PhotoData(10L, "trip.jpg", Origin.TRAVEL))));
        var result = service.owned(1L, null, new Filter("ALL", date, date, 0, "", 100, false));
        assertThat(result.visitsLimited()).isTrue();
        assertThat(result.visits()).hasSize(201);
        assertThat(result.visits().get(0).photos()).containsExactly(new PhotoData(9L, "visit.jpg", Origin.RECORD_MAP));
        assertThat(result.visits().get(199).id()).isEqualTo(200);
        assertThat(result.visits().get(200).origin()).isEqualTo(Origin.TRAVEL);
        assertThat(result.visits().get(200).photos()).containsExactly(new PhotoData(10L, "trip.jpg", Origin.TRAVEL));
        verify(query).visitPhotos(1L, ids);
    }

    @Test void travelPhotoCapSignalsLimitAndOutOfRangeOriginalDoesNotBecomeVisit() {
        when(access.owned(1L, null)).thenReturn(personal);
        when(query.places(eq(personal), any(), eq(true))).thenReturn(new PlacePage(List.of(place(100L)), 1, 1));
        when(query.travelRecords(1L, List.of(100L))).thenReturn(Map.of(100L, original(date.minusDays(1))));
        when(query.travelPhotos(1L, List.of(100L), 1000)).thenReturn(IntStream.range(0, 1000)
                .mapToObj(i -> new TravelPhoto(100L, new PhotoData((long)i, "photo", Origin.TRAVEL))).toList());
        var result = service.owned(1L, null, new Filter("ALL", date, date, 0, "", 100, false));
        assertThat(result.visits()).isEmpty();
        assertThat(result.visitsLimited()).isTrue();
        assertThat(result.places().get(0).visited()).isTrue(); // Visited state is lifetime-based, not date-filtered.
        assertThat(result.places().get(0).visitCount()).isEqualTo(1);
    }

    @Test void emptyOverviewSkipsAllEmptyIdBatchReads() {
        when(access.owned(1L, null)).thenReturn(personal);
        when(query.places(eq(personal), any(), eq(true))).thenReturn(new PlacePage(List.of(), 0, 0));
        var result = service.owned(1L, null, filter());
        assertThat(result.places()).isEmpty();
        assertThat(result.visits()).isEmpty();
        verify(query, never()).visitCounts(any(), any());
        verify(query, never()).visits(any(), any(), any(), any(), anyInt());
        verify(query, never()).visitPhotos(any(), any());
        verify(query, never()).travelRecords(any(), any());
        verify(query, never()).travelPhotos(any(), any(), anyInt());
    }

    @ParameterizedTest @ValueSource(ints = {199, 200, 201})
    void personalVisitCapSignalsTruncationOnlyAboveTwoHundred(int count) {
        when(access.owned(1L, null)).thenReturn(personal);
        when(query.places(eq(personal), any(), eq(true))).thenReturn(new PlacePage(List.of(place(null)), 1, 1));
        when(query.visits(1L, List.of(2L), null, null, 201)).thenReturn(IntStream.range(0, count)
                .mapToObj(i -> new VisitData((long)i, 2L, "visit", null, date, null)).toList());
        var result = service.owned(1L, null, filter());
        assertThat(result.visits()).hasSize(Math.min(count, 200));
        assertThat(result.visitsLimited()).isEqualTo(count > 200);
    }

    @ParameterizedTest @ValueSource(ints = {500, 501})
    void mapCapSignalsTruncationOnlyAboveFiveHundred(long total) {
        when(access.owned(1L, null)).thenReturn(personal);
        when(query.places(eq(personal), any(), eq(true))).thenReturn(new PlacePage(List.of(), total, 2));
        var result = service.owned(1L, null, new Filter("ALL", null, null, 9, "", 100, true));
        assertThat(result.page()).isZero();
        assertThat(result.placesLimited()).isEqualTo(total > 500);
        verify(query).places(personal, new Selection("ALL", null, null, "", new Window(0, 500)), true);
    }

    @Test void unavailableShareDoesNotReachAnyQuery() {
        when(access.shared("revoked")).thenThrow(new NotFoundException("unavailable"));
        assertThatThrownBy(() -> service.shared("revoked", filter())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(query);
    }

    @ParameterizedTest @MethodSource("invalidFilters")
    void invalidFiltersAreRejectedBeforeQuery(Filter filter) {
        when(access.owned(1L, null)).thenReturn(personal);
        assertThatThrownBy(() -> service.owned(1L, null, filter)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(query);
    }

    @Test void hiddenVisitsRejectDateQueryBeforeOtherValidation() {
        when(access.shared("token")).thenReturn(new Scope(1L, 3L, true, false));
        assertThatThrownBy(() -> service.shared("token", new Filter("INVALID", date, null, -1, "", 0, false)))
                .isInstanceOf(BadRequestException.class).hasMessage("방문 기록을 공유하지 않은 지도에서는 날짜를 조회할 수 없습니다.");
        verifyNoInteractions(query);
    }

    @Test void placeVisitsCheckOwnershipAndKeepTwentyItemClampedPaging() {
        when(query.placeVisits(1L, 2L, new Window(0, 20))).thenReturn(List.of(new VisitData(7L, 2L, "title", "memo", date, null)));
        var result = service.placeVisits(1L, 2L, -3);
        assertThat(result).hasSize(1);
        var order = inOrder(access, query);
        order.verify(access).requireOwnedPlace(1L, 2L);
        order.verify(query).placeVisits(1L, 2L, new Window(0, 20));
        order.verify(query).visitPhotos(1L, List.of(7L));
    }

    @Test void travelCandidatesCheckActiveOwnerAndKeepThirtyItemPaging() {
        var page = new CandidatePage(List.of(), new Window(0, 30), 0);
        when(query.travelCandidates(1L, new Window(0, 30))).thenReturn(page);
        assertThat(service.travelCandidates(1L, -1)).isSameAs(page);
        var order = inOrder(access, query);
        order.verify(access).owned(1L, null);
        order.verify(query).travelCandidates(1L, new Window(0, 30));
    }

    private static Stream<Filter> invalidFilters() {
        return Stream.of(new Filter("OTHER", null, null, 0, "", 100, false),
                new Filter("ALL", LocalDate.of(2026,10,15), LocalDate.of(2026,10,14), 0, "", 100, false),
                new Filter("ALL", null, null, -1, "", 100, true),
                new Filter("ALL", null, null, 0, "", 0, false),
                new Filter("ALL", null, null, 0, "", 101, true),
                new Filter("ALL", null, null, 0, "x".repeat(101), 100, false));
    }

    private Filter filter() { return new Filter("ALL", null, null, 0, "", 100, false); }
    private PlaceData place(Long source) { return new PlaceData(2L, "place", "private place memo", "Japan", "Osaka", 34.5, 135.5, false, source, List.of(3L, 4L)); }
    private TravelRecordData original(LocalDate recorded) { return new TravelRecordData(100L, "trip", "private trip memo", recorded, LocalTime.NOON, 8L, "trip plan"); }
}

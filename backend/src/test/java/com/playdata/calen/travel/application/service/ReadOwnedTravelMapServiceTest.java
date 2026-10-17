package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.TravelMapGeometry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReadOwnedTravelMapServiceTest {
    final TravelOwnedMapReadStore store = mock(TravelOwnedMapReadStore.class);
    final TravelOwnedMapCachePort cache = mock(TravelOwnedMapCachePort.class);
    final TravelOwnedMapClusterPort clusters = mock(TravelOwnedMapClusterPort.class);
    final TravelRoutePayloadPort payload = mock(TravelRoutePayloadPort.class);
    final ReadOwnedTravelMapService reads = new ReadOwnedTravelMapService(store, cache, clusters, payload);

    @Test void activeAccountIsCheckedBeforeCacheAndCacheHitSkipsEveryQuery() {
        var cached = empty();
        when(cache.get(7L, 13L)).thenReturn(cached);
        assertThat(reads.overview(7L, 13L)).isSameAs(cached);
        var order = inOrder(store, cache);
        order.verify(store).requireActiveOwner(7L); order.verify(cache).get(7L, 13L);
        verifyNoMoreInteractions(store, cache); verifyNoInteractions(clusters, payload);
    }

    @Test void inactiveOwnerCannotReadPreviouslyCachedValues() {
        doThrow(new NotFoundException("inactive")).when(store).requireActiveOwner(7L);
        assertThatThrownBy(() -> reads.overview(7L, 9L)).hasMessage("inactive");
        assertThatThrownBy(() -> reads.markerBundle(7L, 8L)).hasMessage("inactive");
        assertThatThrownBy(() -> reads.frames(7L)).hasMessage("inactive");
        verifyNoInteractions(cache, clusters, payload);
    }

    @Test void overviewRetainsLoadOrderAndUsesSnapshotRouteSemanticsWithoutOriginalGpxIo() {
        var plan = new TravelPlan(); plan.setId(9L); plan.setName("Trip"); plan.setColorHex("#abcdef");
        var route = new TravelRouteSegment(); route.setId(41L); route.setPlan(plan); route.setRouteDate(LocalDate.of(2026, 10, 16));
        route.setDistanceKm(new BigDecimal("1.2345")); route.setGpxFilesJson("files"); route.setRoutePathJson("path"); route.setLineColorHex(null);
        route.setDurationMinutes(null); route.setTransportMode(null); route.setSourceType(null); route.setLineStyle(null);
        var files = Arrays.asList(new TravelRouteData.GpxFile(null, "opaque-a", null, 1),
                new TravelRouteData.GpxFile(" ", "opaque-b", null, 2));
        when(payload.files("files")).thenReturn(files);
        when(payload.decode("path")).thenReturn(List.of(new TravelRouteData.Point(BigDecimal.ONE, BigDecimal.TEN)));
        var baseline = TravelRouteMapper.result(route, payload, true);
        clearInvocations(payload);
        when(store.planCount(7L)).thenReturn(2);
        when(store.markers(7L)).thenReturn(List.of(new MarkerSummary(marker("#abcdef"), "/existing-photo")));
        when(clusters.getOrBuild(7L, 11L)).thenReturn(new Photos(3, 1, List.of(), List.of()));
        when(store.routes(7L)).thenReturn(List.of(new Route(41L, new TravelRouteData.Plan(9L, "Trip", "#abcdef"),
                new TravelRouteData.Value(route.getRouteDate(), null, null, route.getDistanceKm(), null, null, null, null, null, "path", null, null, null), "files")));
        var result = reads.overview(7L, 11L);
        assertThat(result.planCount()).isEqualTo(2); assertThat(result.markerCount()).isEqualTo(1);
        assertThat(result.photoMarkerCount()).isEqualTo(3); assertThat(result.photoClusterCount()).isEqualTo(1);
        assertThat(result.totalDistanceKm()).isEqualTo(new BigDecimal("1.235"));
        assertThat(result.markers().get(0).marker().colorHex()).isEqualTo("#ABCDEF");
        assertThat(result.markers().get(0).photoUrl()).isEqualTo("/existing-photo");
        assertThat(result.routes()).containsExactly(baseline);
        assertThat(baseline.durationMinutes()).isNull(); assertThat(baseline.gpxFileNames()).containsExactly(null, " ");
        var order = inOrder(store, cache, clusters, payload);
        order.verify(store).requireActiveOwner(7L); order.verify(cache).get(7L, 11L);
        order.verify(store).planCount(7L); order.verify(store).markers(7L);
        order.verify(clusters).getOrBuild(7L, 11L); order.verify(store).routes(7L);
        order.verify(payload).files("files"); order.verify(payload).decode("path");
        order.verify(cache).publish(7L, 11L, result); verifyNoMoreInteractions(payload);
    }

    @Test void clusterFallbackHappensBeforeBadMarkerColorAndFailureCannotPublish() {
        when(store.markers(7L)).thenReturn(List.of(new MarkerSummary(marker("invalid"), null)));
        when(clusters.getOrBuild(7L, null)).thenReturn(new Photos(0, 0, List.of(), List.of()));
        assertThatThrownBy(() -> reads.overview(7L, null)).isInstanceOf(com.playdata.calen.common.exception.BadRequestException.class);
        verify(clusters).getOrBuild(7L, null); verify(store).routes(7L); verify(cache, never()).publish(any(), any(), any());
    }

    @Test void coordinatesAreCheckedBeforeNearestSearchAndDetailHydration() {
        when(store.selectedMarker(7L, 8L)).thenReturn(new Location(8L, null, BigDecimal.ZERO));
        assertThatThrownBy(() -> reads.markerBundle(7L, 8L)).hasMessage("Location coordinates are required for map detail loading.");
        verify(store, never()).nearby(any(), any(), any(), anyInt()); verify(store, never()).details(anyList());
    }

    @Test void radiusExpandsUntilTheWholeEarthAndPreservesSelectedFirstAndStableEqualDistances() {
        var selected = new Location(8L, BigDecimal.ZERO, BigDecimal.ZERO);
        var a = new Location(9L, BigDecimal.ZERO, BigDecimal.ONE);
        var b = new Location(10L, BigDecimal.ZERO, BigDecimal.ONE);
        when(store.selectedMarker(7L, 8L)).thenReturn(selected);
        when(store.nearby(eq(7L), eq(selected), any(), eq(10))).thenReturn(List.of(a, b));
        reads.markerBundle(7L, 8L);
        var bounds = ArgumentCaptor.forClass(Bounds.class);
        verify(store, times(7)).nearby(eq(7L), eq(selected), bounds.capture(), eq(10));
        assertThat(bounds.getAllValues().get(0)).isEqualTo(TravelMapGeometry.around(0, 0, 100));
        assertThat(bounds.getAllValues().get(6)).isEqualTo(TravelMapGeometry.around(0, 0, TravelMapGeometry.MAX_RADIUS_METERS));
        verify(store).details(List.of(selected, a, b));
    }

    @Test void tenCandidatesOutsideTheSphericalRadiusDoNotStopTheBoundingBoxExpansionEarly() {
        var selected = new Location(8L, BigDecimal.ZERO, BigDecimal.ZERO);
        var outside = IntStream.range(0, 10).mapToObj(i -> new Location((long) i + 20, new BigDecimal("0.0008"), new BigDecimal("0.0008"))).toList();
        when(store.selectedMarker(7L, 8L)).thenReturn(selected);
        when(store.nearby(eq(7L), eq(selected), any(), eq(10))).thenReturn(outside);
        reads.markerBundle(7L, 8L);
        verify(store, times(2)).nearby(eq(7L), eq(selected), any(), eq(10));
        var expected = new ArrayList<Location>(); expected.add(selected); expected.addAll(outside);
        verify(store).details(expected);
    }

    @Test void framesKeepTheStoreProjectionWithoutFurtherTransformation() {
        var values = List.of(new Frame(1L, 2L, "Trip", TravelRecordType.LEDGER, "a.jpg", null, null,
                "/opaque", null, null, "Title", null, null, null, null, null, BigDecimal.ONE, BigDecimal.TEN));
        when(store.frames(7L)).thenReturn(values);
        assertThat(reads.frames(7L)).isSameAs(values);
        var order = inOrder(store); order.verify(store).requireActiveOwner(7L); order.verify(store).frames(7L);
        verifyNoInteractions(cache, clusters, payload);
    }

    private static Marker marker(String color) { return new Marker(8L, 9L, "Trip", color, null, null, null, "Place", null, null, null, BigDecimal.ONE, BigDecimal.TEN); }
    private static Overview empty() { return new Overview(0, 0, 0, 0, 0, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of()); }
}

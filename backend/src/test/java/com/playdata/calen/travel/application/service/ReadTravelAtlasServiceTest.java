package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReadTravelAtlasServiceTest {
    final TravelAtlasStore store = mock(TravelAtlasStore.class);
    final TravelAtlasMediaTokenPort tokens = mock(TravelAtlasMediaTokenPort.class);
    final TravelRoutePayloadPort payload = mock(TravelRoutePayloadPort.class);
    final ReadTravelAtlasService reads = new ReadTravelAtlasService(store, tokens, payload, new TravelPhotoClusterPolicy());

    @Test void everyReadChecksActiveViewerBeforeAnyPlanPageOrTokenWork() {
        doThrow(new NotFoundException("inactive")).when(store).requireActiveViewer(7L);
        assertThatThrownBy(() -> reads.overview(7L)).hasMessage("inactive");
        assertThatThrownBy(() -> reads.cluster(7L, 1L, 0, 12, null)).hasMessage("inactive");
        assertThatThrownBy(() -> reads.community(7L, 0, 10)).hasMessage("inactive");
        verify(store, times(3)).requireActiveViewer(7L); verifyNoMoreInteractions(store); verifyNoInteractions(tokens, payload);
    }

    @Test void emptyAtlasStillQueriesPublishedThenRecipientScopeButSkipsChildrenAndReturnsScaledZero() {
        var value = reads.overview(7L);
        assertThat(value.distance()).isEqualTo(new BigDecimal("0.000")); assertThat(value.plans()).isEmpty();
        var order = inOrder(store);
        order.verify(store).requireActiveViewer(7L); order.verify(store).publishedCompletedPlans(); order.verify(store).receivedCompletedPlans(7L);
        verifyNoMoreInteractions(store); verifyNoInteractions(tokens, payload);
    }

    @Test void publicHeroTokenIsResolvedBeforePlanColorValidationAsInTheOriginalAssembler() {
        var plan = plan(true, "invalid");
        when(store.publishedCompletedPlans()).thenReturn(List.of(plan));
        when(store.photos(List.of(10L))).thenReturn(List.of(photo(30L, plan, "37.5", "127")));
        when(tokens.issue(30L)).thenThrow(new IllegalStateException("token source failed"));
        assertThatThrownBy(() -> reads.overview(7L)).hasMessage("token source failed");
        verify(store).routes(List.of(10L)); verify(tokens).issue(30L); verifyNoInteractions(payload);
    }

    @Test void privateRecipientReferencesNeverIssuePublicTokensAndKeepShareMetadata() {
        var plan = plan(false, "#abcdef"); var at = LocalDateTime.of(2026, 10, 16, 1, 2);
        when(store.receivedCompletedPlans(7L)).thenReturn(List.of(new Access(plan, 50L, at)));
        when(store.photos(List.of(10L))).thenReturn(List.of(photo(30L, plan, "37.5", "127")));
        var overview = reads.overview(7L);
        assertThat(overview.plans().get(0).hero()).isEqualTo(new Reference(30L, null, 50L));
        assertThat(overview.plans().get(0).sharing().at()).isEqualTo(at);
        assertThat(overview.plans().get(0).sharing().visibility()).isEqualTo("RESTRICTED");
        assertThat(overview.pins().get(0).photo().share()).isEqualTo(50L);
        verifyNoInteractions(tokens, payload);
    }

    @Test void clusterUsesRepresentativeCoordinatesNotCentroidAndAuthorizesEachMetadataConversionFirst() {
        var plan = plan(true, "#abcdef"); var photo = photo(30L, plan, "37.5", "127");
        when(store.publishedCompletedPlans()).thenReturn(List.of(plan)); when(store.photos(List.of(10L))).thenReturn(List.of(photo));
        var policy = mock(TravelPhotoClusterPolicy.class);
        when(policy.cluster(anyList())).thenReturn(List.of(new TravelPhotoClusterData.PhotoCluster(30L, photo.point(), List.of(photo.point()),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1)));
        var service = new ReadTravelAtlasService(store, tokens, payload, policy);
        when(tokens.issue(30L)).thenReturn("token"); when(store.detail(30L)).thenReturn(detail(30L));
        var result = service.cluster(7L, 30L, 0, 12, null);
        assertThat(result.page().cluster().latitude()).isEqualByComparingTo("37.5");
        assertThat(result.page().cluster().longitude()).isEqualByComparingTo("127");
        var order = inOrder(tokens, store);
        order.verify(store).requireActiveViewer(7L); order.verify(store).publishedCompletedPlans(); order.verify(store).receivedCompletedPlans(7L);
        order.verify(store).photos(List.of(10L)); order.verify(tokens).issue(30L); order.verify(store).detail(30L);
        order.verify(tokens).issue(30L); order.verify(store).detail(30L);
    }

    @Test void communityKeepsRawPageTotalsAfterFilteringAndUsesTheNewestUploaderRatherThanPlanOwner() {
        var memory = memory(20L, TravelRecordType.MEMORY, "#abcdef");
        var noPhotos = memory(21L, TravelRecordType.MEMORY, "#abcdef");
        var ledger = memory(22L, TravelRecordType.LEDGER, "#abcdef");
        when(store.community(0, 20)).thenReturn(new Window(List.of(memory, ledger, noPhotos), 99, 5));
        var at = LocalDateTime.of(2026, 10, 16, 1, 2);
        when(store.communityPhotos(List.of(20L, 21L))).thenReturn(List.of(new FeedPhoto(30L, 20L, at), new FeedPhoto(31L, 20L, at)));
        when(store.hero(31L)).thenReturn(new Hero("Different uploader", "Caption")); when(tokens.issue(31L)).thenReturn("token");
        var feed = reads.community(7L, -1, 100);
        assertThat(feed.items()).hasSize(1); assertThat(feed.total()).isEqualTo(99); assertThat(feed.totalPages()).isEqualTo(5);
        assertThat(feed.size()).isEqualTo(20); assertThat(feed.page()).isZero();
        assertThat(feed.items().get(0).hero().uploadedBy()).isEqualTo("Different uploader");
        assertThat(feed.items().get(0).photo().media()).isEqualTo(31L); assertThat(feed.items().get(0).photoCount()).isEqualTo(2);
        verify(tokens, never()).issue(30L);
    }

    @Test void emptyCommunityWindowDoesNotFetchPhotosAndInvalidColorStopsBeforeUploaderOrTokenWork() {
        when(store.community(0, 10)).thenReturn(new Window(List.of(), 30, 3));
        assertThat(reads.community(7L, null, null).items()).isEmpty(); verify(store, never()).communityPhotos(any());
        when(store.community(0, 10)).thenReturn(new Window(List.of(memory(20L, TravelRecordType.MEMORY, "invalid")), 30, 3));
        when(store.communityPhotos(List.of(20L))).thenReturn(List.of(new FeedPhoto(30L, 20L, null)));
        assertThatThrownBy(() -> reads.community(7L, null, null)).isInstanceOf(com.playdata.calen.common.exception.BadRequestException.class);
        verify(store, never()).hero(any()); verifyNoInteractions(tokens, payload);
    }

    private Plan plan(boolean published, String color) {
        return new Plan(10L, "Trip", null, null, null, TravelPlanStatus.COMPLETED, color, new Owner("owner", "Plan owner"), published, null);
    }
    private Photo photo(Long id, Plan plan, String lat, String lon) {
        var point = new TravelPhotoClusterData.PhotoPoint(id, 20L, plan.id(), plan.name(), plan.color(), LocalDate.of(2026, 10, 16), null,
                "Place", "Memory", null, null, null, new BigDecimal(lat), new BigDecimal(lon), null, false);
        return new Photo(id, plan, point);
    }
    private Memory memory(Long id, TravelRecordType type, String color) {
        return new Memory(new TravelOwnedMapData.Marker(id, 10L, "Trip", color, null, null, null, "Memory", null, null, null, null, null), type, "Memo");
    }
    private TravelClusterDetailData.Media detail(Long id) {
        return new TravelClusterDetailData.Media(id, 10L, "Trip", "#ABCDEF", 20L, TravelRecordType.MEMORY, TravelMediaType.PHOTO,
                "photo.jpg", "image/jpeg", 1L, null, "Uploader", null, null, null, "Memory", null, null, null, null, null, null, null, null, null, null, false);
    }
}

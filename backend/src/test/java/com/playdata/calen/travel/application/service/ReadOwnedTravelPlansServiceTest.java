package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.policy.TravelOwnedPlansPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOwnedTravelPlansServiceTest {
    final TravelOwnedPlansStore store = mock(TravelOwnedPlansStore.class);
    final TravelOwnedPlansCachePort cache = mock(TravelOwnedPlansCachePort.class);
    final TravelRoutePayloadPort payload = mock(TravelRoutePayloadPort.class);
    final ReadOwnedTravelPlansService service = new ReadOwnedTravelPlansService(store, cache, payload);
    @Test void ownerValidationPrecedesEveryCacheHitAndDetailLookup() {
        doThrow(new IllegalStateException("inactive")).when(store).requireActiveOwner(7L);
        assertThatThrownBy(() -> service.plans(7L)).hasMessage("inactive");
        assertThatThrownBy(() -> service.portfolio(7L)).hasMessage("inactive");
        assertThatThrownBy(() -> service.detail(7L, 1L)).hasMessage("inactive");
        verifyNoInteractions(cache, payload); verify(store, never()).detail(any(), any());
    }
    @Test void cacheHitsAreReturnedWithoutRepositoriesNormalizationOrPublication() {
        var plans = List.of(mock(Summary.class)); var portfolio = mock(Portfolio.class);
        when(cache.plans(7L)).thenReturn(plans); when(cache.portfolio(7L)).thenReturn(portfolio);
        assertThat(service.plans(7L)).isSameAs(plans); assertThat(service.portfolio(7L)).isSameAs(portfolio);
        verify(store, times(2)).requireActiveOwner(7L); verifyNoMoreInteractions(store); verifyNoInteractions(payload);
        verify(cache, never()).publishPlans(any(), any()); verify(cache, never()).publishPortfolio(any(), any());
    }
    @Test void emptyMissesPublishTheExistingAllTripsShapeWithoutGpxCalls() {
        when(cache.plans(7L)).thenReturn(null);
        when(store.aggregates(7L)).thenReturn(List.of());
        when(store.portfolio(7L)).thenReturn(new Rows(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThat(service.plans(7L)).isEmpty(); var p = service.portfolio(7L);
        assertThat(p.scopeType()).isEqualTo("ALL"); assertThat(p.name()).isEqualTo("All trips"); assertThat(p.headCount()).isEqualTo(1);
        assertThat(p.metrics().plannedTotalKrw()).isEqualTo(new BigDecimal("0.00")); assertThat(p.metrics().totalDistanceKm()).isEqualTo(new BigDecimal("0.000"));
        verify(cache).publishPlans(7L, List.of()); verify(cache).publishPortfolio(7L, p); verifyNoInteractions(payload);
    }
    @Test void queryFailureDoesNotPublishAndSummaryCountClampingDoesNotOverflow() {
        when(cache.plans(7L)).thenReturn(null);
        when(store.aggregates(7L)).thenThrow(new IllegalStateException("query failed"));
        assertThatThrownBy(() -> service.plans(7L)).hasMessage("query failed"); verify(cache, never()).publishPlans(any(), any());
        var p = new Head(1L, "Trip", null, null, null, "jpy", 2, null, null, null);
        var s = TravelOwnedPlansPolicy.aggregate(new Aggregate(p, new BigDecimal("1.005"), null, Long.MAX_VALUE, -1L, null, 0L, 1L, new BigDecimal("1.23456"), Long.MAX_VALUE, -1L));
        assertThat(s.head().status()).isEqualTo("PLANNED"); assertThat(s.head().currency()).isEqualTo("jpy");
        assertThat(s.metrics().budgetItemCount()).isEqualTo(Integer.MAX_VALUE); assertThat(s.metrics().recordCount()).isZero();
        assertThat(s.metrics().totalDurationMinutes()).isEqualTo(Integer.MAX_VALUE); assertThat(s.metrics().totalStepCount()).isZero();
        assertThat(s.metrics().plannedTotalKrw()).isEqualTo(new BigDecimal("1.01")); assertThat(s.metrics().totalDistanceKm()).isEqualTo(new BigDecimal("1.235"));
    }
}

package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import com.playdata.calen.travel.domain.policy.TravelReferencePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadTravelReferenceServiceTest {
    final TravelReferenceStore store = mock(TravelReferenceStore.class);
    final TravelReferenceRatesPort rates = mock(TravelReferenceRatesPort.class);
    final ReadTravelReferenceService service = new ReadTravelReferenceService(store, rates);
    @Test void inactiveOwnerIsRejectedBeforeCategoryReadsCurrencyValidationOrProviderCalls() {
        doThrow(new IllegalStateException("inactive")).when(store).requireActiveOwner(7L);
        assertThatThrownBy(() -> service.catalog(7L)).hasMessage("inactive");
        assertThatThrownBy(() -> service.rates(7L, "invalid")).hasMessage("inactive");
        verify(store, never()).catalogSource(any()); verifyNoInteractions(rates);
    }
    @Test void currencySelectionRetainsDefaultsInsertionOrderAndExistingEmptySegmentErrors() {
        assertThat(TravelReferencePolicy.currencies(null)).containsExactly("KRW", "USD", "JPY", "CNY", "EUR");
        assertThat(TravelReferencePolicy.currencies("jpy,usd,jpy,")).containsExactly("JPY", "USD", "KRW");
        assertThat(TravelReferencePolicy.currencies(",,")).containsExactly("KRW");
        assertThatThrownBy(() -> service.rates(7L, "usd,,jpy")).hasMessage("Currency code must be a 3-letter ISO 4217 code.");
        verifyNoInteractions(rates);
    }
    @Test void catalogQueriesHaveNoRateSideEffectAndOwnerValidationPrecedesBothReadFlows() {
        when(store.catalogSource(7L)).thenReturn(new Source(List.of(), List.of()));
        var result = service.catalog(7L); assertThat(result.statuses()).containsExactly("PLANNED", "COMPLETED", "SAMPLE");
        verifyNoInteractions(rates);
        service.rates(7L, "jpy"); var order = inOrder(store, rates);
        order.verify(store).requireActiveOwner(7L); order.verify(store).catalogSource(7L); order.verify(store).requireActiveOwner(7L);
        order.verify(rates).latest(argThat(values -> values.stream().toList().equals(List.of("JPY", "KRW"))));
    }
}

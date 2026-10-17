package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelBudgetData.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WriteTravelBudgetServiceTest {
    private final TravelBudgetStore budgets = mock(TravelBudgetStore.class);
    private final TravelRatePort rates = mock(TravelRatePort.class);
    private final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    private final WriteTravelBudgetService service = new WriteTravelBudgetService(budgets, rates, summaries);

    @Test void creationChecksOwnershipThenPersistsNormalizedValuesBeforeInvalidation() {
        when(budgets.create(eq(2L), any())).thenReturn(3L);
        var result = service.create(1L, 2L, draft(" usd ", "1.2345", "3"));
        assertThat(result.id()).isEqualTo(3L);
        assertThat(result.value()).isEqualTo(new Value("Food", "Lunch", new BigDecimal("1.23"), "USD",
                new BigDecimal("3.000000"), new BigDecimal("3.70"), "note", 0));
        // KRW conversion uses the original amount, not the rounded display amount (3.70, not 3.69).
        var order = inOrder(budgets, summaries);
        order.verify(budgets).requireOwnedPlan(1L, 2L);
        order.verify(budgets).create(2L, result.value());
        order.verify(summaries).invalidateOwner(1L);
        verifyNoInteractions(rates);
    }

    @Test void krwUsesOneRegardlessOfProvidedRateWithoutExternalLookup() {
        var result = service.create(1L, 2L, draft("KRW", "12.345", "-1"));
        assertThat(result.value().exchangeRateToKrw()).isEqualTo(new BigDecimal("1.000000"));
        assertThat(result.value().amountKrw()).isEqualTo(new BigDecimal("12.35"));
        verifyNoInteractions(rates);
    }

    @Test void missingForeignRateUsesPortAndSixDigitHalfUpRounding() {
        when(rates.requiredRate("USD")).thenReturn(new BigDecimal("1.0050004"));
        var result = service.update(1L, 3L, draft("USD", "7.776", null));
        assertThat(result.value().amount()).isEqualTo(new BigDecimal("7.78"));
        assertThat(result.value().exchangeRateToKrw()).isEqualTo(new BigDecimal("1.005000"));
        assertThat(result.value().amountKrw()).isEqualTo(new BigDecimal("7.81"));
        verify(budgets).update(3L, result.value());
        verify(summaries).invalidateOwner(1L);
    }

    @Test void foreignRateCannotBeZeroOrNegative() {
        for (String rate : new String[]{"0", "-1"}) {
            assertThatThrownBy(() -> service.create(1L, 2L, draft("USD", "10", rate)))
                    .isInstanceOf(BadRequestException.class).hasMessage("Exchange rate must be greater than 0.");
        }
        verify(budgets, never()).create(any(), any());
        verifyNoInteractions(rates, summaries);
    }

    @Test void invalidCurrencyCannotWriteOrInvalidate() {
        assertThatThrownBy(() -> service.update(1L, 3L, draft("US", "10", "3")))
                .isInstanceOf(BadRequestException.class).hasMessage("Currency code must be a 3-letter ISO 4217 code.");
        verify(budgets, never()).update(any(), any());
        verifyNoInteractions(rates, summaries);
    }

    @Test void nonOwnerCannotTriggerProviderOrPersistenceOrInvalidation() {
        doThrow(new NotFoundException("unavailable")).when(budgets).requireOwnedPlan(1L, 2L);
        assertThatThrownBy(() -> service.create(1L, 2L, draft("USD", "10", null))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(rates, summaries);
        verify(budgets, never()).create(any(), any());
    }

    @Test void providerFailureLeavesPersistenceAndInvalidationUntouched() {
        when(rates.requiredRate("USD")).thenThrow(new BadRequestException("unavailable rate"));
        assertThatThrownBy(() -> service.update(1L, 3L, draft("USD", "10", null))).hasMessage("unavailable rate");
        verify(budgets, never()).update(any(), any());
        verifyNoInteractions(summaries);
    }

    @Test void persistenceFailureCannotRegisterSuccessfulInvalidation() {
        doThrow(new IllegalStateException("storage failed")).when(budgets).update(eq(3L), any());
        assertThatThrownBy(() -> service.update(1L, 3L, draft("KRW", "10", null))).hasMessage("storage failed");
        verifyNoInteractions(summaries);
    }

    @Test void deletionChecksOwnerAndInvalidatesAfterRemovingRow() {
        service.delete(1L, 3L);
        var order = inOrder(budgets, summaries);
        order.verify(budgets).requireOwnedBudget(1L, 3L);
        order.verify(budgets).delete(3L);
        order.verify(summaries).invalidateOwner(1L);
        verifyNoInteractions(rates);
    }

    @Test void memoAndDisplayOrderKeepExistingDefaultsAndExplicitValues() {
        var result = service.create(1L, 2L, new Draft("Food", "Lunch", BigDecimal.TEN, "KRW", null, " \t ", -5));
        assertThat(result.value().memo()).isNull();
        assertThat(result.value().displayOrder()).isEqualTo(-5);
    }

    private Draft draft(String currency, String amount, String rate) {
        return new Draft(" Food ", " Lunch ", new BigDecimal(amount), currency, rate == null ? null : new BigDecimal(rate), " note ", null);
    }
}

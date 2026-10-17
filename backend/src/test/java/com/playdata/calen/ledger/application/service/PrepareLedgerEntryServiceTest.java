package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.application.port.out.LedgerExchangeRatePort;
import com.playdata.calen.ledger.application.port.out.LedgerReferencesPort;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrepareLedgerEntryServiceTest {
    private final LedgerReferencesPort references = mock(LedgerReferencesPort.class);
    private final LedgerExchangeRatePort rates = mock(LedgerExchangeRatePort.class);
    private final PrepareLedgerEntryService service = new PrepareLedgerEntryService(references, rates);
    private final LocalDate date = LocalDate.of(2026, 10, 15);

    @BeforeEach void catalog() {
        when(references.category(1L, 2L)).thenReturn(new Category(2L, EntryType.EXPENSE));
        when(references.payment(1L, EntryType.EXPENSE, 3L)).thenReturn(new Payment(3L, "카드", PaymentMethodKind.CARD));
        when(references.detail(1L, 4L)).thenReturn(new Detail(4L, 2L));
    }

    @Test void localCurrencyAndTextUseTheExistingNormalization() {
        var result = service.prepare(1L, draft(" krw ", BigDecimal.TEN, BigDecimal.ONE, null, null), false);
        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.memo()).isNull();
        assertThat(result.exchange()).isEqualTo(new Exchange(new BigDecimal("7500"), null, null, null, null, null));
        assertThat(result.groupId()).isEqualTo(2L);
        assertThat(result.detailId()).isEqualTo(4L);
        verifyNoInteractions(rates);
    }

    @Test void explicitForeignRateNormalizesBeforeMultiplication() {
        var result = service.prepare(1L, draft(" usd ", new BigDecimal("10.12345"), new BigDecimal("1300.1234567"), null, null), false);
        assertThat(result.exchange().foreignAmount()).isEqualTo(new BigDecimal("10.1235"));
        assertThat(result.exchange().rateToKrw()).isEqualTo(new BigDecimal("1300.123457"));
        assertThat(result.exchange().krwAmount()).isEqualTo(new BigDecimal("13162"));
        assertThat(result.exchange().provider()).isEqualTo("Provided");
        assertThat(result.exchange().rateDate()).isEqualTo(date);
        verifyNoInteractions(rates);
    }

    @Test void missingRateUsesPortAndFallsBackToEntryDate() {
        when(rates.requiredRate("JPY", date)).thenReturn(new Rate(new BigDecimal("9.5"), null, "provider"));
        var result = service.prepare(1L, draft("JPY", BigDecimal.TEN, null, null, null), false);
        assertThat(result.exchange().krwAmount()).isEqualByComparingTo("95");
        assertThat(result.exchange().rateDate()).isEqualTo(date);
        assertThat(result.exchange().provider()).isEqualTo("provider");
    }

    @Test void foreignPreviewRequiresExplicitRateBeforeAnyCatalogAccess() {
        assertThatThrownBy(() -> service.prepare(1L, draft("USD", BigDecimal.TEN, null, null, null), true))
                .isInstanceOf(BadRequestException.class).hasMessage("수정 공유할 외화 거래의 적용 환율을 입력해 주세요.");
        verifyNoInteractions(references, rates);
    }

    @Test void invalidForeignAmountDoesNotRequestRate() {
        assertThatThrownBy(() -> service.prepare(1L, draft("USD", BigDecimal.ZERO, null, null, null), false))
                .isInstanceOf(BadRequestException.class).hasMessage("Foreign amount must be greater than 0.");
        verifyNoInteractions(rates);
    }

    @Test void mismatchedTypeAndDetailAreRejected() {
        when(references.category(1L, 2L)).thenReturn(new Category(2L, EntryType.INCOME));
        assertThatThrownBy(() -> service.prepare(1L, draft(null, null, null, null, null), false)).isInstanceOf(BadRequestException.class);
        when(references.category(1L, 2L)).thenReturn(new Category(2L, EntryType.EXPENSE));
        when(references.detail(1L, 4L)).thenReturn(new Detail(4L, 999L));
        assertThatThrownBy(() -> service.prepare(1L, draft(null, null, null, null, null), false)).isInstanceOf(BadRequestException.class);
    }

    @Test void newTravelEntryChecksPeriodButLinkedRecordRetainsExistingRule() {
        when(references.travelPeriod(1L, 5L)).thenReturn(new TravelPeriod(date.minusDays(2), date.minusDays(1)));
        assertThatThrownBy(() -> service.prepare(1L, draft(null, null, null, 5L, null), false))
                .isInstanceOf(BadRequestException.class).hasMessage("Travel ledger entries must stay within the selected travel period.");
        clearInvocations(references);
        service.prepare(1L, draft(null, null, null, 5L, 6L), false);
        verify(references, never()).travelPeriod(any(), any());
    }

    @Test void incomePreviewRemovesTravelLinksWithoutProvisioningPayment() {
        when(references.category(1L, 2L)).thenReturn(new Category(2L, EntryType.INCOME));
        Draft expense = draft(null, null, null, 5L, 6L);
        Draft income = new Draft(date, expense.entryTime(), expense.title(), expense.memo(), expense.amount(), null, null, null,
                EntryType.INCOME, 2L, 4L, 3L, 5L, 6L);
        var result = service.prepare(1L, income, true);
        assertThat(result.payment()).isEqualTo(new Payment(null, "-", PaymentMethodKind.OTHER));
        assertThat(result.draft().travelPlanId()).isNull();
        assertThat(result.draft().travelRecordId()).isNull();
        verify(references, never()).payment(any(), any(), any());
        verify(references, never()).travelPeriod(any(), any());
        verifyNoInteractions(rates);
    }

    private Draft draft(String currency, BigDecimal amount, BigDecimal rate, Long plan, Long record) {
        return new Draft(date, LocalTime.of(12, 30), "  제목  ", "  ", new BigDecimal("7500"), currency, amount, rate,
                EntryType.EXPENSE, 2L, 4L, 3L, plan, record);
    }
}

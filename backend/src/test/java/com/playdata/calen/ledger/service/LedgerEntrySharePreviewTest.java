package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.adapter.in.transaction.TransactionalLedgerPreparation;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerReferencesAdapter;
import com.playdata.calen.ledger.adapter.out.exchangerate.TravelLedgerExchangeRateAdapter;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerEntrySharePreviewTest {
    @Mock AppUserService users;
    @Mock LedgerEntryRepository entries;
    @Mock LedgerEntryChangeHistoryRepository histories;
    @Mock CategoryGroupRepository groups;
    @Mock CategoryDetailRepository details;
    @Mock PaymentMethodRepository payments;
    @Mock TravelPlanRepository travelPlans;
    @Mock ExchangeRateService rates;
    @Mock ObjectMapper mapper;
    LedgerEntryService service;
    LedgerEntry original;
    CategoryGroup category;
    CategoryDetail detail;
    PaymentMethod payment;

    @BeforeEach void setup() {
        var references = new JpaLedgerReferencesAdapter(groups, details, payments, travelPlans, users);
        var preparation = new TransactionalLedgerPreparation(references, new TravelLedgerExchangeRateAdapter(rates));
        service = new LedgerEntryService(users, entries, histories, groups, details, payments, rates, mapper, preparation, references);
        category = new CategoryGroup(); category.setId(20L); category.setName("식비"); category.setEntryType(EntryType.EXPENSE);
        detail = new CategoryDetail(); detail.setId(21L); detail.setName("배달"); detail.setGroup(category);
        payment = new PaymentMethod(); payment.setId(22L); payment.setName("가족 카드"); payment.setKind(PaymentMethodKind.CARD);
        original = new LedgerEntry(); original.setId(100L); original.setEntryDate(LocalDate.of(2026,10,14));
        original.setTitle("원본 치킨"); original.setMemo("원본 메모"); original.setAmount(new BigDecimal("15000"));
        original.setEntryType(EntryType.EXPENSE); original.setCategoryGroup(category); original.setCategoryDetail(detail); original.setPaymentMethod(payment);
        original.setTravelPlanId(700L); original.setTravelRecordId(800L);
        lenient().when(entries.findByIdAndOwnerIdAndDeletedAtIsNull(100L,1L)).thenReturn(Optional.of(original));
        lenient().when(groups.findByIdAndOwnerId(20L,1L)).thenReturn(Optional.of(category));
        lenient().when(details.findByIdAndGroupOwnerId(21L,1L)).thenReturn(Optional.of(detail));
        lenient().when(payments.findByIdAndOwnerId(22L,1L)).thenReturn(Optional.of(payment));
        lenient().when(groups.getReferenceById(20L)).thenReturn(category);
        lenient().when(details.getReferenceById(21L)).thenReturn(detail);
        lenient().when(payments.getReferenceById(22L)).thenReturn(payment);
    }

    @Test void editedSnapshotUsesSeparateEntityAndNeverUpdatesOriginalOrHistory() {
        var result=service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,null,null,null));
        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.title()).isEqualTo("공유용 절반 금액");
        assertThat(result.memo()).isEqualTo("공유용 거래 메모");
        assertThat(result.amount()).isEqualByComparingTo("7500");
        assertThat(result.entryDate()).isEqualTo(LocalDate.of(2026,10,15));
        assertThat(result.entryTime()).isEqualTo(LocalTime.of(18,24));
        assertThat(result.categoryGroupName()).isEqualTo("식비");
        assertThat(result.paymentMethodName()).isEqualTo("가족 카드");
        assertThat(result.travelPlanId()).isNull(); assertThat(result.travelRecordId()).isNull();
        assertThat(original.getTitle()).isEqualTo("원본 치킨");
        assertThat(original.getMemo()).isEqualTo("원본 메모");
        assertThat(original.getAmount()).isEqualByComparingTo("15000");
        assertThat(original.getEntryDate()).isEqualTo(LocalDate.of(2026,10,14));
        assertThat(original.getTravelPlanId()).isEqualTo(700L);
        verify(entries,never()).save(any()); verifyNoInteractions(histories,travelPlans);
        verify(payments,never()).save(any());
    }

    @Test void incomePreviewDoesNotProvisionOrReuseAnExpensePaymentMethod() {
        category.setEntryType(EntryType.INCOME);
        var result=service.prepareEntryForSharing(1L,100L,request(EntryType.INCOME,null,null,null));
        assertThat(result.entryType()).isEqualTo(EntryType.INCOME);
        assertThat(result.paymentMethodId()).isNull(); assertThat(result.paymentMethodName()).isEqualTo("-");
        verifyNoInteractions(payments,histories); verify(entries,never()).save(any());
    }

    @Test void providedForeignAmountAndRateUseTheNormalLedgerCalculationWithoutExternalCalls() {
        var result=service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,"USD",new BigDecimal("10"),new BigDecimal("1300")));
        assertThat(result.amount()).isEqualByComparingTo("13000");
        assertThat(result.foreignCurrencyCode()).isEqualTo("USD");
        assertThat(result.foreignAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(rates,histories); verify(entries,never()).save(any());
    }

    @Test void anotherOwnersSourceOrCatalogCannotBePreviewed() {
        assertThatThrownBy(()->service.prepareEntryForSharing(2L,100L,request(EntryType.EXPENSE,null,null,null))).isInstanceOf(NotFoundException.class);
        when(groups.findByIdAndOwnerId(20L,1L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,null,null,null))).isInstanceOf(NotFoundException.class);
        verify(entries,never()).save(any()); verifyNoInteractions(histories);
    }

    @Test void foreignShareRequiresAnExplicitRateAndNeverTriggersProviderRequests() {
        assertThatThrownBy(()->service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,"USD",BigDecimal.TEN,null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(rates); verify(entries,never()).save(any());
    }

    @Test void mismatchedCategoryTypeOrDetailIsRejectedWithoutChangingTheSource() {
        category.setEntryType(EntryType.INCOME);
        assertThatThrownBy(()->service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,null,null,null))).isInstanceOf(BadRequestException.class);
        category.setEntryType(EntryType.EXPENSE);
        var other=new CategoryGroup(); other.setId(99L); detail.setGroup(other);
        assertThatThrownBy(()->service.prepareEntryForSharing(1L,100L,request(EntryType.EXPENSE,null,null,null))).isInstanceOf(BadRequestException.class);
        assertThat(original.getTitle()).isEqualTo("원본 치킨"); verify(entries,never()).save(any());
    }

    private LedgerEntryRequest request(EntryType type,String currency,BigDecimal amount,BigDecimal rate) {
        return new LedgerEntryRequest(LocalDate.of(2026,10,15),LocalTime.of(18,24),"  공유용 절반 금액  ","공유용 거래 메모",
                new BigDecimal("7500"),currency,amount,rate,type,20L,21L,22L,999L,888L);
    }
}

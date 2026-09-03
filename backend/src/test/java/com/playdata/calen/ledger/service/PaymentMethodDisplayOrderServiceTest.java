package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.DisplayOrderRequest;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentMethodDisplayOrderServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private PaymentMethodService service;

    @BeforeEach
    void setUp() {
        service = new PaymentMethodService(appUserService, paymentMethodRepository, ledgerEntryRepository);
    }

    @Test
    void reorderStoresTheRequestedPaymentMethodOrder() {
        AppUser owner = owner();
        PaymentMethod card = payment(10L, "카드", 0);
        PaymentMethod cash = payment(20L, "현금", 1);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner);
        when(paymentMethodRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(card, cash));

        var response = service.reorder(USER_ID, new DisplayOrderRequest(List.of(20L, 10L)));

        assertThat(cash.getDisplayOrder()).isZero();
        assertThat(card.getDisplayOrder()).isEqualTo(1);
        assertThat(response).extracting("id").containsExactly(20L, 10L);
    }

    @Test
    void reorderRejectsAnIdFromAnotherCatalog() {
        owner();
        PaymentMethod card = payment(10L, "카드", 0);
        when(paymentMethodRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(card));

        assertThatThrownBy(() -> service.reorder(USER_ID, new DisplayOrderRequest(List.of(99L))))
                .isInstanceOf(BadRequestException.class);

        assertThat(card.getDisplayOrder()).isZero();
    }

    private AppUser owner() {
        AppUser owner = new AppUser();
        owner.setId(USER_ID);
        owner.setLoginId("owner");
        owner.setDisplayName("Owner");
        owner.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner);
        return owner;
    }

    private PaymentMethod payment(Long id, String name, int displayOrder) {
        PaymentMethod payment = new PaymentMethod();
        payment.setId(id);
        payment.setOwner(ownerWithoutMock());
        payment.setName(name);
        payment.setKind(PaymentMethodKind.CARD);
        payment.setDisplayOrder(displayOrder);
        payment.setActive(true);
        return payment;
    }

    private AppUser ownerWithoutMock() {
        AppUser owner = new AppUser();
        owner.setId(USER_ID);
        return owner;
    }
}

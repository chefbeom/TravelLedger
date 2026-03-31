package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.HouseholdAggregatePreferenceService;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseholdAggregatePreferenceServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Test
    void getPreferences_handlesLegacyOrSparseStoredWidgets() {
        HouseholdAggregatePreferenceService service = new HouseholdAggregatePreferenceService(
                appUserRepository,
                paymentMethodRepository,
                new ObjectMapper()
        );

        AppUser user = new AppUser();
        user.setId(1L);
        user.setActive(true);
        user.setHouseholdAggregateSettingsJson("""
                [
                  null,
                  {"kind":"TOTAL","period":"MONTH"},
                  {"kind":"PAYMENT_METHOD","period":"WEEK","paymentMethodId":999},
                  {"kind":"NONE","period":"DAY","amountType":"INCOME"}
                ]
                """);

        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setId(7L);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L))
                .thenReturn(List.of(paymentMethod));

        var response = service.getPreferences(1L);

        assertThat(response.widgets()).hasSize(4);
        assertThat(response.widgets().get(0).kind()).isEqualTo("TOTAL");
        assertThat(response.widgets().get(0).period()).isEqualTo("MONTH");
        assertThat(response.widgets().get(0).amountType()).isEqualTo("NET");
        assertThat(response.widgets().get(1).kind()).isEqualTo("PAYMENT_METHOD");
        assertThat(response.widgets().get(1).paymentMethodId()).isEqualTo(7L);
        assertThat(response.widgets().get(1).amountType()).isEqualTo("NET");
        assertThat(response.widgets().get(2).kind()).isEqualTo("NONE");
        assertThat(response.widgets().get(2).amountType()).isEqualTo("INCOME");
        assertThat(response.widgets().get(3).kind()).isEqualTo("NONE");
        assertThat(response.widgets().get(3).period()).isEqualTo("DAY");
    }
}

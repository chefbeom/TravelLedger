package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregateWidgetRequest;
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
    void getPreferences_defaultsToCurrentSixWidgetPalette() {
        HouseholdAggregatePreferenceService service = new HouseholdAggregatePreferenceService(
                appUserRepository,
                paymentMethodRepository,
                new ObjectMapper()
        );

        AppUser user = activeUser(1L);
        PaymentMethod paymentMethod = paymentMethod(7L);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L))
                .thenReturn(List.of(paymentMethod));

        var response = service.getPreferences(1L);

        assertThat(response.widgets()).hasSize(6);
        assertThat(response.widgets().get(0).kind()).isEqualTo("TOTAL");
        assertThat(response.widgets().get(0).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(1).kind()).isEqualTo("MONTHLY_GOAL");
        assertThat(response.widgets().get(1).period()).isEqualTo("MONTH");
        assertThat(response.widgets().get(2).kind()).isEqualTo("PAYMENT_METHOD");
        assertThat(response.widgets().get(2).paymentMethodId()).isEqualTo(7L);
        assertThat(response.widgets().get(2).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(3).kind()).isEqualTo("MONTHLY_CUMULATIVE_CHART");
        assertThat(response.widgets().get(3).layoutW()).isEqualTo(2);
        assertThat(response.widgets().get(4).kind()).isEqualTo("NONE");
        assertThat(response.widgets().get(5).kind()).isEqualTo("NONE");
        assertThat(response.widgets())
                .extracting(widget -> List.of(widget.layoutY(), widget.layoutH()))
                .containsOnly(List.of(1, 1));
    }

    @Test
    void getPreferences_handlesLegacyOrSparseStoredWidgets() {
        HouseholdAggregatePreferenceService service = new HouseholdAggregatePreferenceService(
                appUserRepository,
                paymentMethodRepository,
                new ObjectMapper()
        );

        AppUser user = activeUser(1L);
        user.setHouseholdAggregateSettingsJson("""
                [
                  null,
                  {"kind":"TOTAL","period":"MONTH"},
                  {"kind":"PAYMENT_METHOD","period":"WEEK","paymentMethodId":999},
                  {"kind":"NONE","period":"DAY","amountType":"INCOME"}
                ]
                """);

        PaymentMethod paymentMethod = paymentMethod(7L);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L))
                .thenReturn(List.of(paymentMethod));

        var response = service.getPreferences(1L);

        assertThat(response.widgets()).hasSize(6);
        assertThat(response.widgets().get(0).kind()).isEqualTo("TOTAL");
        assertThat(response.widgets().get(0).period()).isEqualTo("MONTH");
        assertThat(response.widgets().get(0).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(1).kind()).isEqualTo("PAYMENT_METHOD");
        assertThat(response.widgets().get(1).period()).isEqualTo("WEEK");
        assertThat(response.widgets().get(1).paymentMethodId()).isEqualTo(7L);
        assertThat(response.widgets().get(1).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(2).kind()).isEqualTo("NONE");
        assertThat(response.widgets().get(2).period()).isEqualTo("MONTH");
        assertThat(response.widgets().get(2).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(3).kind()).isEqualTo("MONTHLY_CUMULATIVE_CHART");
        assertThat(response.widgets())
                .extracting(widget -> List.of(widget.layoutW(), widget.layoutH(), widget.layoutOrder()))
                .containsExactly(
                        List.of(1, 1, 1),
                        List.of(1, 1, 2),
                        List.of(1, 1, 3),
                        List.of(2, 1, 4),
                        List.of(1, 1, 5),
                        List.of(1, 1, 6)
                );
    }

    @Test
    void savePreferences_preservesPaymentMethodYearExpenseSelection() {
        HouseholdAggregatePreferenceService service = new HouseholdAggregatePreferenceService(
                appUserRepository,
                paymentMethodRepository,
                new ObjectMapper()
        );

        AppUser user = activeUser(1L);
        PaymentMethod paymentMethod = paymentMethod(7L);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L))
                .thenReturn(List.of(paymentMethod));

        var response = service.savePreferences(1L, new HouseholdAggregatePreferencesRequest(List.of(
                new HouseholdAggregateWidgetRequest("PAYMENT_METHOD", "YEAR", 7L, "EXPENSE", null, null, null, null, null, 1, 1, 1, 1, 1)
        )));

        assertThat(response.widgets()).hasSize(6);
        assertThat(response.widgets().get(0).kind()).isEqualTo("PAYMENT_METHOD");
        assertThat(response.widgets().get(0).period()).isEqualTo("YEAR");
        assertThat(response.widgets().get(0).paymentMethodId()).isEqualTo(7L);
        assertThat(response.widgets().get(0).amountType()).isEqualTo("EXPENSE");
        assertThat(response.widgets().get(0).layoutW()).isEqualTo(1);
        assertThat(response.widgets().get(0).layoutH()).isEqualTo(1);
    }

    @Test
    void savePreferences_preservesAndNormalizesWidgetLayout() {
        HouseholdAggregatePreferenceService service = new HouseholdAggregatePreferenceService(
                appUserRepository,
                paymentMethodRepository,
                new ObjectMapper()
        );

        AppUser user = activeUser(1L);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L))
                .thenReturn(List.of());

        var response = service.savePreferences(1L, new HouseholdAggregatePreferencesRequest(List.of(
                new HouseholdAggregateWidgetRequest("TOTAL", "MONTH", null, "NET", null, null, null, null, null, 1, 1, 1, 1, 3),
                new HouseholdAggregateWidgetRequest("MONTHLY_CUMULATIVE_CHART", "MONTH", null, "NET", null, null, true, true, true, 2, 1, 4, 1, 1),
                new HouseholdAggregateWidgetRequest("MONTHLY_GOAL", "MONTH", null, "EXPENSE", 400000L, 100000L, null, null, null, 1, 1, 2, 1, 2),
                new HouseholdAggregateWidgetRequest("NONE", "DAY", null, "NET", null, null, null, null, null, 3, 1, 3, 2, 4)
        )));

        assertThat(response.widgets()).hasSize(6);
        assertThat(response.widgets().get(0).layoutX()).isEqualTo(6);
        assertThat(response.widgets().get(0).layoutY()).isEqualTo(1);
        assertThat(response.widgets().get(0).layoutW()).isEqualTo(1);
        assertThat(response.widgets().get(0).layoutH()).isEqualTo(1);
        assertThat(response.widgets().get(0).layoutOrder()).isEqualTo(3);
        assertThat(response.widgets().get(1).layoutX()).isEqualTo(2);
        assertThat(response.widgets().get(1).layoutW()).isEqualTo(4);
        assertThat(response.widgets().get(1).layoutH()).isEqualTo(1);
        assertThat(response.widgets().get(1).layoutOrder()).isEqualTo(1);
        assertThat(response.widgets().get(2).layoutX()).isEqualTo(1);
        assertThat(response.widgets().get(2).layoutW()).isEqualTo(1);
        assertThat(response.widgets().get(2).layoutH()).isEqualTo(1);
        assertThat(response.widgets().get(2).layoutOrder()).isEqualTo(2);
        assertThat(response.widgets().get(3).layoutX()).isEqualTo(7);
        assertThat(response.widgets().get(3).layoutW()).isEqualTo(1);
        assertThat(response.widgets().get(3).layoutH()).isEqualTo(1);
        assertThat(response.widgets().get(3).layoutOrder()).isEqualTo(4);
    }

    private AppUser activeUser(Long id) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setActive(true);
        return user;
    }

    private PaymentMethod paymentMethod(Long id) {
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setId(id);
        return paymentMethod;
    }
}

package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TravelPlanPolicyTest {
    private final LocalDate start = LocalDate.of(2026, 10, 10);
    private final LocalDate end = LocalDate.of(2026, 10, 15);

    @Test void preparationNormalizesTextCurrencyStatusAndColorWithoutChangingDatesOrHeadCount() {
        assertThat(TravelPlanPolicy.prepare(new Draft(" Trip ", " Japan ", start, end, " krw ", 2, " completed ", " #a0b1c2 ", " note ")))
                .isEqualTo(new Value("Trip", "Japan", start, end, "KRW", 2, TravelPlanStatus.COMPLETED, "#A0B1C2", "note"));
    }

    @Test void optionalFieldsAndDefaultsMatchTheExistingPlanContract() {
        var value = TravelPlanPolicy.prepare(new Draft("Trip", "  ", start, start, "KRW", 1, null, null, " \t "));
        assertThat(value.destination()).isNull();
        assertThat(value.memo()).isNull();
        assertThat(value.startDate()).isEqualTo(value.endDate());
        assertThat(value.status()).isEqualTo(TravelPlanStatus.PLANNED);
        assertThat(value.colorHex()).isEqualTo("#3182F6");
    }

    @Test void reversedDatesAreRejectedBeforeOtherInvalidValues() {
        assertThatThrownBy(() -> TravelPlanPolicy.prepare(new Draft("Trip", null, end, start, "X", 1, "UNKNOWN", "bad", null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Travel start date cannot be after end date.");
    }

    @Test void invalidCurrencyKeepsTheExistingErrorMessage() {
        assertThatThrownBy(() -> TravelPlanPolicy.prepare(new Draft("Trip", null, start, end, "X", 1, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Currency code must be a 3-letter ISO 4217 code.");
    }

    @ParameterizedTest @EnumSource(TravelPlanStatus.class)
    void everySupportedStatusWorksAsInputAndStoredValue(TravelPlanStatus status) {
        assertThat(TravelPlanPolicy.status(" " + status.name().toLowerCase(java.util.Locale.ROOT) + " ")).isEqualTo(status);
        assertThat(TravelPlanPolicy.status(status)).isEqualTo(status);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void missingStatusDefaultsToPlanned(String status) {
        assertThat(TravelPlanPolicy.status(status)).isEqualTo(TravelPlanStatus.PLANNED);
    }

    @Test void missingStoredStatusDefaultsToPlanned() {
        assertThat(TravelPlanPolicy.status((TravelPlanStatus)null)).isEqualTo(TravelPlanStatus.PLANNED);
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "FINISHED"})
    void unsupportedStatusIsRejected(String status) {
        assertThatThrownBy(() -> TravelPlanPolicy.status(status)).isInstanceOf(BadRequestException.class).hasMessage("Unsupported travel status.");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void missingColorUsesTheExistingDefault(String color) {
        assertThat(TravelPlanPolicy.color(color)).isEqualTo("#3182F6");
    }

    @ParameterizedTest @ValueSource(strings = {"#12345", "123456", "#GG0000"})
    void invalidColorIsRejected(String color) {
        assertThatThrownBy(() -> TravelPlanPolicy.color(color)).isInstanceOf(BadRequestException.class).hasMessage("Travel color must use #RRGGBB format.");
    }
}

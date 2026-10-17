package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.common.exception.BadRequestException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TravelCoordinatePolicyTest {
    @Test void coordinatesMustBeOmittedTogetherOrProvidedTogether() {
        TravelCoordinatePolicy.requirePair(null, null);
        TravelCoordinatePolicy.requirePair(BigDecimal.ONE, BigDecimal.ONE);
        assertThatThrownBy(() -> TravelCoordinatePolicy.requirePair(null, BigDecimal.ONE)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> TravelCoordinatePolicy.requirePair(BigDecimal.ONE, null)).isInstanceOf(BadRequestException.class);
        assertThat(TravelCoordinatePolicy.normalize(null, true)).isNull();
    }

    @ParameterizedTest @CsvSource({"37.12345675,true,37.1234568", "-127.12345675,false,-127.1234568",
            "90.00000004,true,90.0000000", "-90.00000004,true,-90.0000000", "180.00000004,false,180.0000000", "-180.00000004,false,-180.0000000"})
    void existingSevenDecimalRoundingOccursBeforeRangeValidation(String raw, boolean latitude, String expected) {
        assertThat(TravelCoordinatePolicy.normalize(new BigDecimal(raw), latitude)).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest @CsvSource({"90.00000005,true", "-90.00000005,true", "180.00000005,false", "-180.00000005,false"})
    void roundedCoordinatesOutsideRangeKeepTheExistingError(String raw, boolean latitude) {
        assertThatThrownBy(() -> TravelCoordinatePolicy.normalize(new BigDecimal(raw), latitude)).isInstanceOf(BadRequestException.class)
                .hasMessage(latitude ? "Latitude must be between -90 and 90." : "Longitude must be between -180 and 180.");
    }
}

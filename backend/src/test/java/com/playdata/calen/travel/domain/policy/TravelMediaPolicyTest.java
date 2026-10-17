package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.travel.domain.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TravelMediaPolicyTest {
    @Test void memoriesForcePhotosWhileLedgerDefaultsAndReceiptChoiceRemainUnchanged() {
        assertThat(TravelMediaPolicy.type(TravelRecordType.MEMORY,TravelMediaType.RECEIPT)).isEqualTo(TravelMediaType.PHOTO);
        assertThat(TravelMediaPolicy.type(TravelRecordType.LEDGER,null)).isEqualTo(TravelMediaType.PHOTO);
        assertThat(TravelMediaPolicy.type(TravelRecordType.LEDGER,TravelMediaType.RECEIPT)).isEqualTo(TravelMediaType.RECEIPT);
    }
    @Test void storedTypeGuardKeepsCaseSensitiveLegacyImageAndPdfRules() {
        TravelMediaPolicy.requireStoredType(TravelMediaType.PHOTO,"image/png");TravelMediaPolicy.requireStoredType(TravelMediaType.RECEIPT,"application/pdf");
        assertThatThrownBy(()->TravelMediaPolicy.requireStoredType(null,"application/pdf")).hasMessage("Photos must use JPG, PNG, WEBP, GIF, or BMP files.");
        assertThatThrownBy(()->TravelMediaPolicy.requireStoredType(TravelMediaType.RECEIPT,"text/plain")).hasMessage("Receipts must use JPG, PNG, WEBP, GIF, BMP, or PDF files.");
        assertThatThrownBy(()->TravelMediaPolicy.requireStoredType(TravelMediaType.PHOTO,"IMAGE/PNG")).isInstanceOf(RuntimeException.class);
    }
    @Test void gpsNeedsBothCoordinatesRoundsSevenPlacesAndDoesNotAddNewRangeValidation() {
        assertThat(TravelMediaPolicy.gps(BigDecimal.ONE,null)).isNull();assertThat(TravelMediaPolicy.gps(null,BigDecimal.ONE)).isNull();
        var gps=TravelMediaPolicy.gps(new BigDecimal("91.12345675"),new BigDecimal("181.12345674"));
        assertThat(gps.latitude()).isEqualTo(new BigDecimal("91.1234568"));assertThat(gps.longitude()).isEqualTo(new BigDecimal("181.1234567"));
    }
    @Test void captionUsesTheSameTrimAndNullRules(){assertThat(TravelMediaPolicy.caption("  text  ")).isEqualTo("text");assertThat(TravelMediaPolicy.caption(" ")).isNull();}
}

package com.playdata.calen.sharing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.sharing.adapter.support.RecordShareContractMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ShareSnapshotCodecTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ShareSnapshotCodec codec = new ShareSnapshotCodec(mapper);

    @Test void snapshotJsonRemainsByteForByteCompatibleIncludingForeignAmountsAndMinuteFormatting() throws Exception {
        var legacy = new LedgerEntryResponse(100L, LocalDate.of(2026, 10, 15), LocalTime.of(18, 24, 37), "식사", "거래 메모",
                new BigDecimal("15000.00"), "USD", new BigDecimal("10.5000"), new BigDecimal("1428.571429"),
                LocalDate.of(2026, 10, 15), "Provided", EntryType.EXPENSE, 1L, "식비", 2L, "외식", 3L, "카드",
                PaymentMethodKind.CARD, 4L, 5L);
        String previousJson = mapper.writeValueAsString(legacy);
        String actual = codec.encode(RecordShareContractMapper.snapshot(legacy));
        assertThat(actual).isEqualTo(previousJson);
        var expectedResponse = mapper.readValue(previousJson, LedgerEntryResponse.class);
        assertThat(RecordShareContractMapper.ledgerResponse(codec.decode(actual))).isEqualTo(expectedResponse);
        assertThat(codec.fingerprint(actual)).isEqualTo(codec.fingerprint(previousJson));
        assertThat(mapper.readTree(actual).path("entryTime").asText()).isEqualTo("18:24");
        assertThat(legacy.entryTime()).isEqualTo(LocalTime.of(18, 24, 37));
    }

    @Test void absentTravelSnapshotAndChangedContentKeepTheirEqualitySemantics() {
        assertThat(codec.encode(null)).isNull(); assertThat(codec.decode(null)).isNull(); assertThat(codec.fingerprint(null)).isNull();
        assertThat(codec.fingerprint("{}")).isNotEqualTo(codec.fingerprint("{ }"));
        assertThat(codec.fingerprint("{\"amount\":1}")).isNotEqualTo(codec.fingerprint("{\"amount\":2}"));
    }

    @Test void unreadableSnapshotUsesTheExistingErrorContractWithoutLeakingItsContent() {
        assertThatThrownBy(() -> codec.decode("invalid private content"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unable to read shared record.").hasNoCause();
    }
}

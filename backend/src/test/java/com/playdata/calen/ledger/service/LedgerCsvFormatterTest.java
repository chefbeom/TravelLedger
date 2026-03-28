package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerCsvFormatterTest {

    @Test
    void formatsImportCompatibleColumnsWithUtf8Bom() {
        LedgerEntryResponse entry = new LedgerEntryResponse(
                1L,
                LocalDate.of(2026, 3, 28),
                LocalTime.of(9, 30),
                "=SUM(A1:A2)",
                "\"quoted\" memo",
                new BigDecimal("12345"),
                EntryType.EXPENSE,
                10L,
                "식비",
                11L,
                "카페",
                12L,
                "신한카드",
                PaymentMethodKind.CARD
        );

        byte[] csv = LedgerCsvFormatter.format(List.of(entry));

        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);

        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"거래일\",\"구분\",\"내용\",\"금액\",\"결제수단\",\"대분류\",\"소분류\""));
        assertTrue(text.contains("\"지출\""));
        assertTrue(text.contains("\"'=SUM(A1:A2)\""));
        assertTrue(text.contains("\"신한카드\",\"식비\",\"카페\""));
    }

    @Test
    void buildsReadableFileName() {
        assertEquals(
                "ledger-2026-03-01_to_2026-03-31.csv",
                LedgerCsvFormatter.buildFileName(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        );
    }
}

package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record LedgerExcelPreviewRowResponse(
        int previewIndex,
        String sourceSheetName,
        Integer sourceRowNumber,
        LocalDate entryDate,
        LocalTime entryTime,
        String title,
        String memo,
        BigDecimal amount,
        EntryType entryType,
        String paymentMethodName,
        String categoryGroupName,
        String categoryDetailName,
        boolean ready,
        List<String> issues
) {
}

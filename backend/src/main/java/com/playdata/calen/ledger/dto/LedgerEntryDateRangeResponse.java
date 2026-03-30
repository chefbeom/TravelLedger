package com.playdata.calen.ledger.dto;

import java.time.LocalDate;

public record LedgerEntryDateRangeResponse(
        LocalDate earliestDate,
        LocalDate latestDate
) {
}

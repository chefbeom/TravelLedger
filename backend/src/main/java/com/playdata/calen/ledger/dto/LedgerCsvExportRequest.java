package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record LedgerCsvExportRequest(
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate from,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate to
) {
}

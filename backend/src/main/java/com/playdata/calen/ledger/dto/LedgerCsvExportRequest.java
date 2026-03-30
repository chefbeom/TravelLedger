package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record LedgerCsvExportRequest(
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate from,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate to,
        @NotBlank
        String secondaryPin
) {
}

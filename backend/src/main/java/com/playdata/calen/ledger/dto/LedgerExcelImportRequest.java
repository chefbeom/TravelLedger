package com.playdata.calen.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record LedgerExcelImportRequest(
        @NotEmpty(message = "At least one row is required.")
        List<@Valid LedgerExcelImportRowRequest> rows
) {
}

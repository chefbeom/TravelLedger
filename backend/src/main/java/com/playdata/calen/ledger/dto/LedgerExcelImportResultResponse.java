package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerExcelImportResultResponse(
        int requestedCount,
        int importedCount,
        int skippedCount,
        List<String> createdCategoryGroups,
        List<String> createdCategoryDetails,
        List<String> createdPaymentMethods,
        List<String> warnings
) {
}

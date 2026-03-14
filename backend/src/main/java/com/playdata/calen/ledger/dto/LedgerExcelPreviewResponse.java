package com.playdata.calen.ledger.dto;

import java.util.List;

public record LedgerExcelPreviewResponse(
        String fileName,
        String sheetName,
        Integer headerRowNumber,
        int detectedRowCount,
        int readyRowCount,
        int skippedRowCount,
        List<String> notes,
        List<LedgerExcelPreviewRowResponse> rows
) {
}

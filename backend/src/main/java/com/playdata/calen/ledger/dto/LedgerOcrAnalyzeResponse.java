package com.playdata.calen.ledger.dto;

import java.util.List;
import java.util.Map;

public record LedgerOcrAnalyzeResponse(
        String documentType,
        String rawText,
        LedgerOcrEntrySuggestionResponse suggestedEntry,
        List<LedgerOcrEntrySuggestionResponse> suggestedEntries,
        List<LedgerOcrLineItemResponse> lineItems,
        Double confidence,
        List<String> warnings,
        String vendor,
        String paymentMethodText,
        String categoryText,
        Map<String, Object> timing
) {
}

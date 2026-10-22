package com.playdata.calen.ledger.dto;

import java.util.List;

/** Badge metadata only: no image URLs, OCR text, prompts or transaction content. */
public record LedgerImageReviewSummaryResponse(List<ReviewRequest> requests) {
    public record ReviewRequest(Long analysisId, int entryCount, List<Integer> approvedEntryIndexes) {}
}

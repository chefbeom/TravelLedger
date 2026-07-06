package com.playdata.calen.ledger.dto;

import java.time.LocalDateTime;

public record LedgerImageAnalysisHistoryResponse(
        Long id,
        String clientRequestId,
        String status,
        String documentType,
        String fileName,
        String contentType,
        long fileSizeBytes,
        String summary,
        String errorMessage,
        String rawText,
        LedgerOcrAnalyzeResponse result,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        boolean imageAvailable,
        String imageUrl
) {
}

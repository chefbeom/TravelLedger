package com.playdata.calen.ledger.dto;

import java.time.LocalDateTime;

public record LedgerImageAnalysisHistoryResponse(
        Long id,
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
        LocalDateTime cancelledAt
) {
}

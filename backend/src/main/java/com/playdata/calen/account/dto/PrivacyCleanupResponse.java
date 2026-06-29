package com.playdata.calen.account.dto;

import java.time.LocalDateTime;

public record PrivacyCleanupResponse(
        int aiAnalysisHistoriesDeleted,
        int publicDownloadLinksRevoked,
        int travelPublicMediaSharesRevoked,
        int photoLocationMetadataRemoved,
        LocalDateTime processedAt
) {
}
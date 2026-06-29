package com.playdata.calen.account.service;

import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PrivacyManagementService {

    private final LedgerAiAnalysisHistoryRepository ledgerAiAnalysisHistoryRepository;
    private final DriveDownloadLinkRepository driveDownloadLinkRepository;

    public PrivacyCleanupResponse deleteAiAnalysisHistories(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int deleted = ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(userId);
        return new PrivacyCleanupResponse(deleted, 0, processedAt);
    }

    public PrivacyCleanupResponse revokePublicDownloadLinks(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int revoked = driveDownloadLinkRepository.revokeAllActiveByOwnerId(userId, processedAt);
        return new PrivacyCleanupResponse(0, revoked, processedAt);
    }

    public PrivacyCleanupResponse cleanupSensitiveData(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int deleted = ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(userId);
        int revoked = driveDownloadLinkRepository.revokeAllActiveByOwnerId(userId, processedAt);
        return new PrivacyCleanupResponse(deleted, revoked, processedAt);
    }
}
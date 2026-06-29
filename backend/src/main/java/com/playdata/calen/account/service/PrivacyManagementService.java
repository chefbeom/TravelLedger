package com.playdata.calen.account.service;

import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
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
    private final TravelPlanRepository travelPlanRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;

    public PrivacyCleanupResponse deleteAiAnalysisHistories(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int deleted = ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(userId);
        return new PrivacyCleanupResponse(deleted, 0, 0, 0, processedAt);
    }

    public PrivacyCleanupResponse revokePublicDownloadLinks(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int revoked = driveDownloadLinkRepository.revokeAllActiveByOwnerId(userId, processedAt);
        return new PrivacyCleanupResponse(0, revoked, 0, 0, processedAt);
    }

    public PrivacyCleanupResponse revokeTravelPublicMediaShares(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int revoked = revokeTravelPublicMediaSurfaces(userId);
        return new PrivacyCleanupResponse(0, 0, revoked, 0, processedAt);
    }

    public PrivacyCleanupResponse removePhotoLocationMetadata(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int removed = travelMediaAssetRepository.clearGpsMetadataByPlanOwnerId(userId);
        return new PrivacyCleanupResponse(0, 0, 0, removed, processedAt);
    }

    public PrivacyCleanupResponse cleanupSensitiveData(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int deleted = ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(userId);
        int revoked = driveDownloadLinkRepository.revokeAllActiveByOwnerId(userId, processedAt);
        int travelRevoked = revokeTravelPublicMediaSurfaces(userId);
        int photoLocationRemoved = travelMediaAssetRepository.clearGpsMetadataByPlanOwnerId(userId);
        return new PrivacyCleanupResponse(deleted, revoked, travelRevoked, photoLocationRemoved, processedAt);
    }

    private int revokeTravelPublicMediaSurfaces(Long userId) {
        int publicPlansRevoked = travelPlanRepository.revokePublicSharingByOwnerId(userId);
        int communityRecordsRevoked = travelExpenseRecordRepository.revokeCommunitySharingByOwnerId(userId);
        return publicPlansRevoked + communityRecordsRevoked;
    }
}
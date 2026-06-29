package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.account.service.PrivacyManagementService;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrivacyManagementServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private LedgerAiAnalysisHistoryRepository ledgerAiAnalysisHistoryRepository;

    @Mock
    private DriveDownloadLinkRepository driveDownloadLinkRepository;

    @Mock
    private TravelPlanRepository travelPlanRepository;

    @Mock
    private TravelExpenseRecordRepository travelExpenseRecordRepository;

    private PrivacyManagementService service;

    @BeforeEach
    void setUp() {
        service = new PrivacyManagementService(
                ledgerAiAnalysisHistoryRepository,
                driveDownloadLinkRepository,
                travelPlanRepository,
                travelExpenseRecordRepository
        );
    }

    @Test
    void revokePublicDownloadLinksScopesUpdateToCurrentOwner() {
        when(driveDownloadLinkRepository.revokeAllActiveByOwnerId(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(2);

        PrivacyCleanupResponse response = service.revokePublicDownloadLinks(USER_ID);

        ArgumentCaptor<LocalDateTime> processedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(driveDownloadLinkRepository).revokeAllActiveByOwnerId(eq(USER_ID), processedAtCaptor.capture());
        verifyNoInteractions(ledgerAiAnalysisHistoryRepository, travelPlanRepository, travelExpenseRecordRepository);

        assertThat(response.aiAnalysisHistoriesDeleted()).isZero();
        assertThat(response.publicDownloadLinksRevoked()).isEqualTo(2);
        assertThat(response.travelPublicMediaSharesRevoked()).isZero();
        assertThat(response.processedAt()).isEqualTo(processedAtCaptor.getValue());
        assertThat(response.processedAt()).isNotNull();
    }

    @Test
    void revokeTravelPublicMediaSharesScopesPlanAndCommunityRecordUpdatesToCurrentOwner() {
        when(travelPlanRepository.revokePublicSharingByOwnerId(USER_ID)).thenReturn(2);
        when(travelExpenseRecordRepository.revokeCommunitySharingByOwnerId(USER_ID)).thenReturn(3);

        PrivacyCleanupResponse response = service.revokeTravelPublicMediaShares(USER_ID);

        verify(travelPlanRepository).revokePublicSharingByOwnerId(USER_ID);
        verify(travelExpenseRecordRepository).revokeCommunitySharingByOwnerId(USER_ID);
        verifyNoInteractions(ledgerAiAnalysisHistoryRepository, driveDownloadLinkRepository);

        assertThat(response.aiAnalysisHistoriesDeleted()).isZero();
        assertThat(response.publicDownloadLinksRevoked()).isZero();
        assertThat(response.travelPublicMediaSharesRevoked()).isEqualTo(5);
        assertThat(response.processedAt()).isNotNull();
    }

    @Test
    void cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerShares() {
        when(ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(USER_ID)).thenReturn(3);
        when(driveDownloadLinkRepository.revokeAllActiveByOwnerId(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(5);
        when(travelPlanRepository.revokePublicSharingByOwnerId(USER_ID)).thenReturn(2);
        when(travelExpenseRecordRepository.revokeCommunitySharingByOwnerId(USER_ID)).thenReturn(4);

        PrivacyCleanupResponse response = service.cleanupSensitiveData(USER_ID);

        ArgumentCaptor<LocalDateTime> processedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ledgerAiAnalysisHistoryRepository).deleteAllByOwnerId(USER_ID);
        verify(driveDownloadLinkRepository).revokeAllActiveByOwnerId(eq(USER_ID), processedAtCaptor.capture());
        verify(travelPlanRepository).revokePublicSharingByOwnerId(USER_ID);
        verify(travelExpenseRecordRepository).revokeCommunitySharingByOwnerId(USER_ID);

        assertThat(response.aiAnalysisHistoriesDeleted()).isEqualTo(3);
        assertThat(response.publicDownloadLinksRevoked()).isEqualTo(5);
        assertThat(response.travelPublicMediaSharesRevoked()).isEqualTo(6);
        assertThat(response.processedAt()).isEqualTo(processedAtCaptor.getValue());
    }
}
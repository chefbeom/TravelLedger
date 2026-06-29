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

    private PrivacyManagementService service;

    @BeforeEach
    void setUp() {
        service = new PrivacyManagementService(
                ledgerAiAnalysisHistoryRepository,
                driveDownloadLinkRepository
        );
    }

    @Test
    void revokePublicDownloadLinksScopesUpdateToCurrentOwner() {
        when(driveDownloadLinkRepository.revokeAllActiveByOwnerId(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(2);

        PrivacyCleanupResponse response = service.revokePublicDownloadLinks(USER_ID);

        ArgumentCaptor<LocalDateTime> processedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(driveDownloadLinkRepository).revokeAllActiveByOwnerId(eq(USER_ID), processedAtCaptor.capture());
        verifyNoInteractions(ledgerAiAnalysisHistoryRepository);

        assertThat(response.aiAnalysisHistoriesDeleted()).isZero();
        assertThat(response.publicDownloadLinksRevoked()).isEqualTo(2);
        assertThat(response.processedAt()).isEqualTo(processedAtCaptor.getValue());
        assertThat(response.processedAt()).isNotNull();
    }

    @Test
    void cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerLinks() {
        when(ledgerAiAnalysisHistoryRepository.deleteAllByOwnerId(USER_ID)).thenReturn(3);
        when(driveDownloadLinkRepository.revokeAllActiveByOwnerId(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(5);

        PrivacyCleanupResponse response = service.cleanupSensitiveData(USER_ID);

        ArgumentCaptor<LocalDateTime> processedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ledgerAiAnalysisHistoryRepository).deleteAllByOwnerId(USER_ID);
        verify(driveDownloadLinkRepository).revokeAllActiveByOwnerId(eq(USER_ID), processedAtCaptor.capture());

        assertThat(response.aiAnalysisHistoriesDeleted()).isEqualTo(3);
        assertThat(response.publicDownloadLinksRevoked()).isEqualTo(5);
        assertThat(response.processedAt()).isEqualTo(processedAtCaptor.getValue());
    }
}
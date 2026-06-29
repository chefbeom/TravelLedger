package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerAiAnalysisHistoryRetentionServiceTest {

    @Mock
    private LedgerAiAnalysisHistoryRepository historyRepository;

    @Test
    void scheduledCleanupSkipsWhenRetentionIsDisabled() {
        LedgerAiAnalysisHistoryRetentionService service = newService(false, 180);

        service.runScheduledRetentionCleanup();

        verifyNoInteractions(historyRepository);
    }

    @Test
    void deleteExpiredHistoriesUsesConfiguredRetentionCutoff() {
        LedgerAiAnalysisHistoryRetentionService service = newService(true, 90);
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 3, 15);
        LocalDateTime expectedCutoff = LocalDateTime.of(2026, 4, 1, 3, 15);
        when(historyRepository.deleteByCreatedAtBefore(expectedCutoff)).thenReturn(5);

        int deletedCount = service.deleteExpiredHistories(now);

        assertThat(deletedCount).isEqualTo(5);
        verify(historyRepository).deleteByCreatedAtBefore(expectedCutoff);
    }

    @Test
    void retentionDaysAreClampedToAtLeastOneDay() {
        LedgerAiAnalysisHistoryRetentionService service = newService(true, 0);
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 3, 15);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        service.deleteExpiredHistories(now);

        verify(historyRepository).deleteByCreatedAtBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 29, 3, 15));
    }

    @Test
    void scheduledCleanupRecordsFailureWithoutThrowing() {
        LedgerAiAnalysisHistoryRetentionService service = newService(true, 180);
        doThrow(new IllegalStateException("database unavailable"))
                .when(historyRepository)
                .deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class));

        service.runScheduledRetentionCleanup();

        verify(historyRepository).deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void scheduledCleanupRunsWhenEnabled() {
        LedgerAiAnalysisHistoryRetentionService service = newService(true, 180);

        service.runScheduledRetentionCleanup();

        verify(historyRepository).deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        verify(historyRepository, never()).deleteAllByOwnerId(org.mockito.ArgumentMatchers.anyLong());
    }

    private LedgerAiAnalysisHistoryRetentionService newService(boolean enabled, int retentionDays) {
        return new LedgerAiAnalysisHistoryRetentionService(
                historyRepository,
                enabled,
                retentionDays,
                "0 15 3 * * *",
                "Asia/Seoul"
        );
    }
}
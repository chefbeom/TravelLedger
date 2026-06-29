package com.playdata.calen.ledger.ai;

import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerAiAnalysisNotifications {

    private final UserNotificationService userNotificationService;

    public void notifyCompleted(Long userId, LedgerAiAnalysisHistory history) {
        notifyAiAnalysis(
                userId,
                history,
                "AI_ANALYSIS_DONE",
                "AI analysis completed",
                "Your ledger AI analysis is ready."
        );
    }

    public void notifyFailed(Long userId, LedgerAiAnalysisHistory history) {
        notifyAiAnalysis(
                userId,
                history,
                "AI_OR_OCR_FAILED",
                "AI analysis failed",
                "Your ledger AI analysis could not be completed. Please review the AI status and retry."
        );
    }

    private void notifyAiAnalysis(
            Long userId,
            LedgerAiAnalysisHistory history,
            String type,
            String title,
            String message
    ) {
        try {
            userNotificationService.createSystemNotification(
                    userId,
                    type,
                    title,
                    message,
                    "/statistics?aiAnalysisHistoryId=" + history.getId(),
                    "{\"historyId\":" + history.getId() + ",\"status\":\"" + history.getStatus().name() + "\"}"
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create ledger AI notification: historyId={}, type={}", history.getId(), type, exception);
        }
    }
}
package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class LedgerAiAnalysisReportMerger {

    private final LedgerAiAnalysisTextSanitizer aiText;

    LedgerAiAnalysisReportResponse merge(LedgerAiAnalysisReportResponse fallback, LedgerAiRemoteResponse remote) {
        LedgerAiAnalysisReportResponse remoteReport = remote.report();
        return new LedgerAiAnalysisReportResponse(
                firstNonBlank(remoteReport == null ? null : remoteReport.keySummary(), firstNonBlank(remote.summary(), fallback.keySummary())),
                firstNonBlank(remoteReport == null ? null : remoteReport.fullReport(), fallback.fullReport()),
                firstNonBlank(remoteReport == null ? null : remoteReport.averageAmountInsight(), fallback.averageAmountInsight()),
                firstNonEmpty(remote.highlights(), firstNonEmpty(remoteReport == null ? null : remoteReport.notableSpending(), fallback.notableSpending())),
                firstNonEmpty(remote.fixedCostInsights(), firstNonEmpty(remoteReport == null ? null : remoteReport.regularSpending(), fallback.regularSpending())),
                firstNonEmpty(remote.unusualSpendingInsights(), firstNonEmpty(remote.warnings(), firstNonEmpty(remoteReport == null ? null : remoteReport.abnormalSpending(), fallback.abnormalSpending()))),
                firstNonBlank(remoteReport == null ? null : remoteReport.topPaymentMethod(), fallback.topPaymentMethod()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.subscriptions(), fallback.subscriptions()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.fixedExpenses(), fallback.fixedExpenses()),
                firstNonEmpty(remoteReport == null ? null : remoteReport.improvementActions(), firstNonEmpty(remote.recommendations(), fallback.improvementActions())),
                firstNonEmpty(remote.trendInsights(), firstNonEmpty(remoteReport == null ? null : remoteReport.comparisonFocus(), fallback.comparisonFocus()))
        );
    }

    private String firstNonBlank(String primary, String fallback) {
        return aiText.hasText(primary) ? primary : aiText.safeText(fallback);
    }

    private List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }
}
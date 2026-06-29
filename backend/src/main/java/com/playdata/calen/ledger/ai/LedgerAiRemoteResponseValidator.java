package com.playdata.calen.ledger.ai;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.Collection;

public final class LedgerAiRemoteResponseValidator {

    private LedgerAiRemoteResponseValidator() {
    }

    public static LedgerAiRemoteResponse requireUsable(LedgerAiRemoteResponse response, String providerName) {
        String provider = hasText(providerName) ? providerName : "AI provider";
        if (response == null) {
            throw new BadRequestException(provider + " AI 분석 응답이 비어 있습니다.");
        }
        if (Boolean.FALSE.equals(response.ok())) {
            throw new BadRequestException(hasText(response.error())
                    ? response.error()
                    : provider + " AI 분석 요청이 실패했습니다.");
        }
        if (!hasUsableAnalysis(response)) {
            throw new BadRequestException(provider + " AI 분석 응답에 사용할 수 있는 분석 내용이 없습니다.");
        }
        return response;
    }

    private static boolean hasUsableAnalysis(LedgerAiRemoteResponse response) {
        return hasText(response.summary())
                || hasText(response.nextPeriodForecast())
                || hasText(response.habitAssessment())
                || hasAny(response.highlights())
                || hasAny(response.warnings())
                || hasAny(response.risks())
                || hasAny(response.recommendations())
                || hasAny(response.categoryInsights())
                || hasAny(response.paymentInsights())
                || hasAny(response.trendInsights())
                || hasAny(response.unusualSpendingInsights())
                || hasAny(response.fixedCostInsights())
                || hasUsableReport(response.report());
    }

    private static boolean hasUsableReport(LedgerAiAnalysisReportResponse report) {
        if (report == null) {
            return false;
        }
        return hasText(report.keySummary())
                || hasText(report.fullReport())
                || hasText(report.averageAmountInsight())
                || hasText(report.topPaymentMethod())
                || hasAny(report.notableSpending())
                || hasAny(report.regularSpending())
                || hasAny(report.abnormalSpending())
                || hasAny(report.subscriptions())
                || hasAny(report.fixedExpenses())
                || hasAny(report.improvementActions())
                || hasAny(report.comparisonFocus());
    }

    private static boolean hasAny(Collection<String> values) {
        return values != null && values.stream().anyMatch(LedgerAiRemoteResponseValidator::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

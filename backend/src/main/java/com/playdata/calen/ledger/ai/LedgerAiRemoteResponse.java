package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerAiRemoteResponse(
        Boolean ok,
        String error,
        String summary,
        List<String> highlights,
        List<String> warnings,
        @JsonAlias({"risks", "riskSignals"})
        List<String> risks,
        List<String> recommendations,
        @JsonAlias({"categoryInsights", "category_insights"})
        List<String> categoryInsights,
        @JsonAlias({"paymentInsights", "payment_insights"})
        List<String> paymentInsights,
        @JsonAlias({"trendInsights", "trend_insights"})
        List<String> trendInsights,
        @JsonAlias({"unusualSpendingInsights", "unusual_spending_insights", "anomalyInsights"})
        List<String> unusualSpendingInsights,
        @JsonAlias({"fixedCostInsights", "fixed_cost_insights", "subscriptionInsights"})
        List<String> fixedCostInsights,
        @JsonAlias({"nextPeriodForecast", "next_period_forecast", "forecast"})
        String nextPeriodForecast,
        @JsonAlias({"habitAssessment", "habit_assessment"})
        String habitAssessment,
        @JsonAlias({"report", "analysisReport", "spendingReport"})
        LedgerAiAnalysisReportResponse report
) {
}
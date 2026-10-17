package com.playdata.calen.ledger.adapter.support;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisMetrics;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisAdmissionPolicy;

/** Reuse the validated owner entity and original field/config-read order for sync and async drafts. */
public final class LedgerAnalysisHistoryDrafts {
    private LedgerAnalysisHistoryDrafts() {}
    public static LedgerAiAnalysisHistory create(AppUser owner, AnalysisPlan plan,
            LedgerAiAnalysisProperties properties, LedgerAiAnalysisMetrics aiMetrics) {
        LedgerAiAnalysisHistory history = new LedgerAiAnalysisHistory();
        history.setOwner(owner);
        history.setFocusPrompt(plan.focusPrompt() == null ? "" : plan.focusPrompt());
        history.setMode(plan.mode());
        history.setPeriodType(plan.periodType());
        history.setComparisonPreset(plan.comparisonPreset());
        history.setFromDate(plan.primaryRange().from());
        history.setToDate(plan.primaryRange().to());
        if (plan.comparisonRange() != null) {
            history.setCompareFromDate(plan.comparisonRange().from());
            history.setCompareToDate(plan.comparisonRange().to());
        }
        history.setModel(properties.getModel());
        history.setProvider(aiMetrics.providerLabel());
        history.setTitle(LedgerAnalysisAdmissionPolicy.buildTitle(plan));
        return history;
    }
}

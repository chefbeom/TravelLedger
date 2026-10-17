package com.playdata.calen.ledger.domain.policy;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;

public final class LedgerAnalysisAdmissionPolicy {
    private LedgerAnalysisAdmissionPolicy() {}
    public static String buildTitle(AnalysisPlan plan) {
        String range = plan.primaryRange().from() + " ~ " + plan.primaryRange().to();
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && plan.comparisonRange() != null) {
            if (plan.comparisonPreset() == LedgerAiComparisonPreset.WEEKLY_DIGEST) {
                return "AI weekly spending digest - " + range + " vs " + plan.comparisonRange().from() + " ~ " + plan.comparisonRange().to();
            }
            return "AI comparison analysis - " + range + " vs " + plan.comparisonRange().from() + " ~ " + plan.comparisonRange().to();
        }
        return "AI period analysis - " + range;
    }
}

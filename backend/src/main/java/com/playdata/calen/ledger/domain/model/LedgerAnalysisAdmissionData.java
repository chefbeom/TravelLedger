package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.DateRange;

/** Component names/order also preserve the existing record string used by the admission key. */
public final class LedgerAnalysisAdmissionData {
    private LedgerAnalysisAdmissionData() {}
    public record AnalysisPlan(
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset,
            DateRange primaryRange,
            DateRange comparisonRange,
            String focusPrompt
    ) {
    }
}

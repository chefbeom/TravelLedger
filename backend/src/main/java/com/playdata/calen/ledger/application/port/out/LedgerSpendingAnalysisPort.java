package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;

/** Analyze only a prepared/minimized dataset; no ledger mutation or approval capability. */
public interface LedgerSpendingAnalysisPort {
    Result analyze(Request payload);
}
